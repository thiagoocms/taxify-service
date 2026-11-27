package db.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V68, V69 e V70 sobre dados que ja existiam: o banco e levado ate a V66 (a ultima antes das colunas de auditoria), as
 * linhas de terms_acceptances, firm_notifications e recovery_tasks sao criadas no esquema antigo, e so entao a migracao
 * roda. Confere o backfill (created_by = system, created_date = accepted_at / sent_at, deleted = 0), que os dados
 * ficam intactos, que rodar de novo nao muda nada e que o Flyway valida.
 *
 * Usa um banco descartavel proprio (taxify_qa_migr) do MySQL local - criado e apagado pelo teste; so com
 * DB_HOST=localhost. Nunca toca o banco da aplicacao.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
class Phase1AuditMigrationsOnExistingDataTest {

    private static final String SCRATCH = "taxify_qa_migr";
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
        return "jdbc:mysql://localhost:" + port + "/" + database + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private JdbcTemplate jdbc(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(url(database), user, password));
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(url(SCRATCH), user, password).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").target(target).load();
    }

    @Test
    void theAuditColumnsAreBackfilledOnExistingRows() {
        // 1. the schema as it was before V68-V70
        flyway("66").migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'terms_acceptances'",
                String.class, SCRATCH)).doesNotContain("created_by", "deleted");

        // 2. rows written by the old version
        Timestamp now = Timestamp.valueOf(LocalDateTime.of(2026, 11, 26, 9, 0));
        String firmId = UUID.randomUUID().toString();
        String documentId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', 'Escritorio QA Migracao')", firmId, now);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', '52998224725', 'CPF')", documentId, now);
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login, name, email, password)"
                + " VALUES (?, 0, ?, 'qa', 'ADMIN', ?, ?, 'qa.migracao', 'Dona QA', 'dona@example.com', 'x')", userId, now, firmId, documentId);
        Timestamp acceptedAt = Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 8, 30, 15, 123_456_000));
        Timestamp sentAt = Timestamp.valueOf(LocalDateTime.of(2026, 12, 6, 9, 0, 1, 654_321_000));
        jdbc.update("INSERT INTO terms_acceptances (id, user_id, accounting_firm_id, terms_version, accepted_at, ip) VALUES (?, ?, ?, 'T-1', ?, '203.0.113.7')",
                "11111111-1111-1111-1111-111111111111", userId, firmId, acceptedAt);
        jdbc.update("INSERT INTO firm_notifications (id, accounting_firm_id, type, reference_date, sent_at) VALUES (?, ?, 'TRIAL_D3', ?, ?)",
                "22222222-2222-2222-2222-222222222222", firmId, java.sql.Date.valueOf(LocalDate.of(2026, 12, 9)), sentAt);
        Timestamp recoveryCreated = Timestamp.valueOf(LocalDateTime.of(2027, 1, 4, 10, 0, 0, 111_111_000));
        jdbc.update("INSERT INTO recovery_tasks (id, accounting_firm_id, scope_id, company_id, source, period_start, period_end, page_cursor, status,"
                        + " attempts, last_error, created_date) VALUES (?, ?, ?, NULL, 'EFISCO', '2026-12-01', '2026-12-31', 0, 'PENDING', 2, 'ERRO', ?)",
                "33333333-3333-3333-3333-333333333333", firmId, firmId, recoveryCreated);

        // 3. V68-V70
        assertThat(flyway("latest").migrate().migrationsExecuted).isGreaterThanOrEqualTo(3);

        Map<String, Object> terms = jdbc.queryForMap("SELECT * FROM terms_acceptances WHERE id = '11111111-1111-1111-1111-111111111111'");
        assertThat(terms.get("created_by")).isEqualTo("system");
        assertThat(terms.get("created_date")).isEqualTo(acceptedAt.toLocalDateTime());
        assertThat(terms.get("deleted")).isIn(false, 0);
        assertThat(terms.get("last_modified_by")).isNull();
        // the data stays as it was
        assertThat(terms.get("terms_version")).isEqualTo("T-1");
        assertThat(terms.get("ip")).isEqualTo("203.0.113.7");
        assertThat(terms.get("accepted_at")).isEqualTo(acceptedAt.toLocalDateTime());
        assertThat(terms.get("user_id")).isEqualTo(userId);

        Map<String, Object> notice = jdbc.queryForMap("SELECT * FROM firm_notifications WHERE id = '22222222-2222-2222-2222-222222222222'");
        assertThat(notice.get("created_by")).isEqualTo("system");
        assertThat(notice.get("created_date")).isEqualTo(sentAt.toLocalDateTime());
        assertThat(notice.get("deleted")).isIn(false, 0);
        assertThat(notice.get("type")).isEqualTo("TRIAL_D3");
        assertThat(notice.get("reference_date").toString()).isEqualTo("2026-12-09");
        assertThat(notice.get("sent_at")).isEqualTo(sentAt.toLocalDateTime());

        Map<String, Object> task = jdbc.queryForMap("SELECT * FROM recovery_tasks WHERE id = '33333333-3333-3333-3333-333333333333'");
        assertThat(task.get("created_by")).isEqualTo("system");
        assertThat(task.get("created_date")).as("created_date already existed and is kept").isEqualTo(recoveryCreated.toLocalDateTime());
        assertThat(task.get("deleted")).isIn(false, 0);
        assertThat(task.get("status")).isEqualTo("PENDING");
        assertThat(((Number) task.get("attempts")).intValue()).isEqualTo(2);
        assertThat(task.get("last_error")).isEqualTo("ERRO");

        // the unique key survived: the same notice for the same firm and date is still refused
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO firm_notifications (id, accounting_firm_id, type, reference_date, sent_at, deleted, created_by, created_date)"
                        + " VALUES (UUID(), ?, 'TRIAL_D3', '2026-12-09', NOW(), 0, 'qa', NOW())", firmId))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        // 4. nothing more to do on a second run, and Flyway validates the history
        assertThat(flyway("latest").migrate().migrationsExecuted).isZero();
        flyway("latest").validate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM terms_acceptances WHERE created_by = 'system'", Integer.class)).isOne();
    }
}
