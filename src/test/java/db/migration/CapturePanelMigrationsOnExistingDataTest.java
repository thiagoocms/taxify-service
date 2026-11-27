package db.migration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V76 a V79 (painel da captura, spec 2026-11-27) sobre dados que já existiam: o banco vai até a V75, recebe uma empresa,
 * uma nota e uma linha de controle no esquema antigo, e só então as quatro migrações rodam. Confere as tabelas novas
 * vazias, as colunas do mês anterior nulas, os índices da V78, que os dados antigos ficam, que a V78 rodada de novo
 * aceita o índice existente e que, com o lock de metadados preso por outra transação, ela desiste em
 * {@code lock_wait_timeout} (30 s) com o aviso do checklist do deploy, sem travar e sem cair - e devolve o
 * {@code lock_wait_timeout} da sessão.
 *
 * <p>Banco descartável próprio (taxify_qa_migr3) do MySQL local, criado e apagado pelo teste; só com DB_HOST=localhost.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
class CapturePanelMigrationsOnExistingDataTest {

    private static final String SCRATCH = "taxify_qa_migr3";
    private String port;
    private String user;
    private String password;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger migrationLog = (Logger) LoggerFactory.getLogger(V78__CapturePanelSourceIndexes.class);

    @BeforeEach
    void createScratchDatabase() {
        port = System.getenv().getOrDefault("DB_PORT", "3306");
        user = System.getenv().getOrDefault("DB_USER", "root");
        password = System.getenv().getOrDefault("DB_PASSWORD", "root");
        JdbcTemplate admin = jdbc("");
        admin.execute("DROP DATABASE IF EXISTS " + SCRATCH);
        admin.execute("CREATE DATABASE " + SCRATCH + " CHARACTER SET latin1");
        logs.start();
        migrationLog.addAppender(logs);
    }

    @AfterEach
    void dropScratchDatabase() {
        migrationLog.detachAppender(logs);
        jdbc("").execute("DROP DATABASE IF EXISTS " + SCRATCH);
    }

    private String url(String database) {
        return "jdbc:mysql://localhost:" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private JdbcTemplate jdbc(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(url(database), user, password));
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(url(SCRATCH), user, password).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").target(target).load();
    }

    private List<String> indexColumns(JdbcTemplate jdbc, String table, String index) {
        return jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema = ? AND table_name = ?"
                + " AND index_name = ? ORDER BY seq_in_index", String.class, SCRATCH, table, index);
    }

    @Test
    void v76ToV79OnExistingData() throws Exception {
        flyway("75").migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES ('f1', 0, NOW(), 'qa', 'Escritório QA')");
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES ('d1', 0, NOW(), 'qa', '11222333000181', 'CNPJ')");
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES ('c1', 0, NOW(), 'qa', 'f1', 'd1', 'Empresa QA', 1)");
        jdbc.update("INSERT INTO fiscal_xml_documents (id, deleted, created_date, created_by, document_type, source_system, company_id,"
                + " xml_file_path) VALUES ('n1', 0, NOW(), 'qa', 'NFE', 'SIEG', 'c1', 'x.xml')");
        jdbc.update("INSERT INTO fiscal_xml_sync_control (id, company_id, deleted, created_by, created_date, total_sieg, total_importado,"
                + " ultima_verificacao_em) VALUES ('s1', 'c1', 0, 'qa', NOW(), 200, 180, NOW())");

        flyway("79").migrate();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_stage_status", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests", Integer.class)).isZero();
        Map<String, Object> control = jdbc.queryForMap("SELECT total_sieg, total_importado, received_prev_month,"
                + " received_prev_total_sieg, issued_prev_imported FROM fiscal_xml_sync_control WHERE id = 's1'");
        assertThat(control).containsEntry("total_sieg", 200).containsEntry("total_importado", 180)
                .containsEntry("received_prev_month", null).containsEntry("received_prev_total_sieg", null)
                .containsEntry("issued_prev_imported", null);
        assertThat(indexColumns(jdbc, "fiscal_xml_documents", "idx_fxd_company_source_created"))
                .containsExactly("company_id", "source_system", "created_date");
        assertThat(indexColumns(jdbc, "service_invoices", "idx_service_invoices_company_source_created"))
                .containsExactly("company_id", "source_system", "created_date");
        assertThat(indexColumns(jdbc, "capture_priority_requests", "uk_capture_priority_requests_open_key")).containsExactly("open_key");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_xml_documents", Integer.class)).isEqualTo(1);
        assertThat(flyway("79").validateWithResult().validationSuccessful).isTrue();

        // run again by hand: the existing index is accepted
        try (Connection connection = DriverManager.getConnection(url(SCRATCH), user, password)) {
            new V78__CapturePanelSourceIndexes().migrate(context(connection));
        }
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("Index idx_fxd_company_source_created already on fiscal_xml_documents"));
    }

    /** The deploy never hangs on a long transaction: 30 s waiting for the metadata lock, then the warning and no index. */
    @Test
    void v78GivesUpOnABusyTableWithTheDeployWarning() throws Exception {
        flyway("79").migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        jdbc.execute("ALTER TABLE service_invoices DROP INDEX idx_service_invoices_company_source_created");

        try (Connection holder = DriverManager.getConnection(url(SCRATCH), user, password);
             Connection migration = DriverManager.getConnection(url(SCRATCH), user, password)) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement(); ResultSet ignored = statement.executeQuery(
                    "SELECT * FROM service_invoices LIMIT 1")) {
                // the open transaction holds a metadata lock on the table until it ends
                try (Statement before = migration.createStatement()) {
                    before.execute("SET SESSION lock_wait_timeout = 31536000");
                }
                long started = System.currentTimeMillis();
                new V78__CapturePanelSourceIndexes().migrate(context(migration));
                long waited = System.currentTimeMillis() - started;
                assertThat(waited).isBetween(25_000L, 90_000L);
                try (Statement after = migration.createStatement();
                     ResultSet lockWait = after.executeQuery("SELECT @@SESSION.lock_wait_timeout")) {
                    lockWait.next();
                    assertThat(lockWait.getLong(1)).as("the session value is given back").isEqualTo(31536000L);
                }
            } finally {
                holder.rollback();
            }
        }
        assertThat(indexColumns(jdbc, "service_invoices", "idx_service_invoices_company_source_created")).isEmpty();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .startsWith("capture.panel.index.not.created index=idx_service_invoices_company_source_created table=service_invoices:"
                        + " online DDL refused or metadata lock not obtained in 30 s - create it by hand off hours: ALTER TABLE"
                        + " service_invoices ADD INDEX idx_service_invoices_company_source_created (company_id, source_system,"
                        + " created_date), ALGORITHM=INPLACE, LOCK=NONE ("));
    }

    private static Context context(Connection connection) {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }
}
