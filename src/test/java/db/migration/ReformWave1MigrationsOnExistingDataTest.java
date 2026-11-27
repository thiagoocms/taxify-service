package db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V80 to V84 (Reforma Tributária, Onda 1: F1a-0 and F5a) over data that already existed: the database goes up to V79,
 * gets a company and a year typed by hand in the old shape of {@code ibs_cbs_rates}, and only then the five migrations
 * run. Checks the 2026 seed (AL-RN-07), the hand-typed year out of the engine (PENDING), the rates now nullable, the
 * new tables empty with their keys, that the old application's INSERT still works, and that V80 run again changes
 * nothing.
 *
 * <p>Own scratch database (taxify_qa_reform1) of the local MySQL, created and dropped by the test; only with
 * DB_HOST=localhost.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
class ReformWave1MigrationsOnExistingDataTest {

    private static final String SCRATCH = "taxify_qa_reform1";
    private String port;
    private String user;
    private String password;

    @BeforeEach
    void createScratchDatabase() {
        port = System.getenv().getOrDefault("DB_PORT", "3306");
        user = System.getenv().getOrDefault("DB_USER", "root");
        password = System.getenv().getOrDefault("DB_PASSWORD", "root");
        JdbcTemplate admin = jdbc("");
        admin.execute("DROP DATABASE IF EXISTS " + SCRATCH);
        admin.execute("CREATE DATABASE " + SCRATCH + " CHARACTER SET latin1");
    }

    @AfterEach
    void dropScratchDatabase() {
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
    void v80ToV84OnExistingData() throws Exception {
        flyway("79").migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES ('f1', 0, NOW(), 'qa', 'Escritório QA')");
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES ('d1', 0, NOW(), 'qa', '11222333000181', 'CNPJ')");
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES ('c1', 0, NOW(), 'qa', 'f1', 'd1', 'Empresa QA', 1)");
        // a year typed by hand before the console existed (fictitious numbers)
        jdbc.update("INSERT INTO ibs_cbs_rates (ano, aliquota_cbs, aliquota_ibs_uf, aliquota_ibs_mun) VALUES (2027, 8.8, 0.1, 0)");

        flyway("84").migrate();

        // AL-RN-07: 2026 is LAW with the seeded basis and version 0; the rates don't change
        Map<String, Object> y2026 = jdbc.queryForMap("SELECT * FROM ibs_cbs_rates WHERE ano = 2026");
        assertThat(y2026).containsEntry("status", "LAW").containsEntry("version", 0)
                .containsEntry("legal_basis", V80__IbsCbsRateStatus.SEED_LEGAL_BASIS)
                .containsEntry("source_url", "https://www.planalto.gov.br/ccivil_03/leis/lcp/lcp214.htm")
                .containsEntry("changed_by", "system").containsEntry("created_by", "system");
        assertThat((BigDecimal) y2026.get("aliquota_cbs")).isEqualByComparingTo("0.9");
        assertThat((BigDecimal) y2026.get("aliquota_ibs_uf")).isEqualByComparingTo("0.1");
        assertThat((BigDecimal) y2026.get("aliquota_ibs_mun")).isEqualByComparingTo("0");
        assertThat(y2026.get("deleted")).isIn(false, Boolean.FALSE, 0);
        // the hand-typed year stays, out of the engine until reviewed
        assertThat(jdbc.queryForObject("SELECT status FROM ibs_cbs_rates WHERE ano = 2027", String.class)).isEqualTo("PENDING");
        // the rates accept null now (PENDING)
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ?"
                        + " AND table_name = 'ibs_cbs_rates' AND column_name LIKE 'aliquota%' AND is_nullable = 'YES'", String.class, SCRATCH))
                .containsExactlyInAnyOrder("aliquota_cbs", "aliquota_ibs_uf", "aliquota_ibs_mun");
        // the old application's INSERT (no new column) still works alongside
        jdbc.update("INSERT INTO ibs_cbs_rates (ano, aliquota_cbs, aliquota_ibs_uf, aliquota_ibs_mun) VALUES (2030, 1, 1, 1)");
        assertThat(jdbc.queryForObject("SELECT version FROM ibs_cbs_rates WHERE ano = 2030", Integer.class)).isZero();

        // new tables, empty, with their keys
        for (String table : List.of("ibs_cbs_rate_changes", "routine_runs", "stock_credit_surveys", "stock_credit_survey_events")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }
        assertThat(indexColumns(jdbc, "ibs_cbs_rate_changes", "idx_ibs_cbs_rate_changes_year_at")).containsExactly("rate_year", "changed_at");
        assertThat(indexColumns(jdbc, "routine_runs", "uk_routine_runs_routine_date")).containsExactly("routine", "run_date");
        assertThat(indexColumns(jdbc, "stock_credit_surveys", "uk_stock_credit_surveys_company_year")).containsExactly("company_id", "survey_year");
        assertThat(indexColumns(jdbc, "stock_credit_surveys", "idx_scs_year_class")).containsExactly("survey_year", "classification");
        assertThat(indexColumns(jdbc, "stock_credit_survey_events", "idx_scs_events_survey_at")).containsExactly("survey_id", "changed_at");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema = ?"
                + " AND constraint_name IN ('fk_stock_credit_surveys_company', 'fk_stock_credit_survey_events_survey')", Integer.class, SCRATCH))
                .isEqualTo(2);

        // a survey row gets the defaults; the unique key refuses a second one for the same company and year
        jdbc.update("INSERT INTO stock_credit_surveys (id, company_id, survey_year, created_by, created_date) VALUES ('s1', 'c1', 2026, 'qa', NOW())");
        assertThat(jdbc.queryForMap("SELECT contact_status, version FROM stock_credit_surveys WHERE id = 's1'"))
                .containsEntry("contact_status", "NOT_EVALUATED").containsEntry("version", 0);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO stock_credit_surveys (id, company_id, survey_year, created_by, created_date)"
                + " VALUES ('s2', 'c1', 2026, 'qa', NOW())")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(flyway("84").validateWithResult().validationSuccessful).isTrue();

        // V80 again by hand: nothing changes (idempotent seed)
        jdbc.update("UPDATE ibs_cbs_rates SET legal_basis = 'Art. fictício' WHERE ano = 2026");
        try (Connection connection = DriverManager.getConnection(url(SCRATCH), user, password)) {
            new V80__IbsCbsRateStatus().migrate(context(connection));
        }
        assertThat(jdbc.queryForObject("SELECT legal_basis FROM ibs_cbs_rates WHERE ano = 2026", String.class)).isEqualTo("Art. fictício");
    }

    /**
     * V80's ALTER is mandatory for the new schema: with the metadata lock held by a long transaction it gives up in
     * lock_wait_timeout (30 s) with a clear error - the start stops instead of hanging - gives the session value back,
     * and the next run (lock free) goes through.
     */
    @Test
    void v80FailsClearlyOnABusyTable() throws Exception {
        flyway("79").migrate();
        try (Connection holder = DriverManager.getConnection(url(SCRATCH), user, password);
             Connection migration = DriverManager.getConnection(url(SCRATCH), user, password)) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement(); ResultSet ignored = statement.executeQuery(
                    "SELECT * FROM ibs_cbs_rates LIMIT 1")) {
                try (Statement before = migration.createStatement()) {
                    before.execute("SET SESSION lock_wait_timeout = 31536000");
                }
                long started = System.currentTimeMillis();
                assertThatThrownBy(() -> new V80__IbsCbsRateStatus().migrate(context(migration)))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith("ibs.cbs.rates.alter.failed: metadata lock on ibs_cbs_rates not obtained in 30 s");
                assertThat(System.currentTimeMillis() - started).isBetween(25_000L, 90_000L);
                try (Statement after = migration.createStatement();
                     ResultSet lockWait = after.executeQuery("SELECT @@SESSION.lock_wait_timeout")) {
                    lockWait.next();
                    assertThat(lockWait.getLong(1)).as("the session value is given back").isEqualTo(31536000L);
                }
            } finally {
                holder.rollback();
            }
            new V80__IbsCbsRateStatus().migrate(context(migration));
        }
        assertThat(jdbc(SCRATCH).queryForObject("SELECT status FROM ibs_cbs_rates WHERE ano = 2026", String.class)).isEqualTo("LAW");
    }

    private static Context context(Connection connection) {
        Context context = mock(Context.class);
        when(context.getConnection()).thenReturn(connection);
        return context;
    }
}
