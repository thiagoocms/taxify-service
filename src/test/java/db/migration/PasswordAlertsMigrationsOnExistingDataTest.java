package db.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V71 a V75 (spec 2026-11-27) sobre dados que ja existiam: o banco e levado ate a V70, usuarios, empresa e pendencias
 * sao criados no esquema antigo, e so entao as cinco migracoes rodam. Confere os defaults das colunas novas nas linhas
 * existentes (session_version 0, contador 0, datas nulas - o token antigo, sem "sv", continua valendo), as tabelas
 * novas vazias, as chaves unicas e estrangeiras, que os dados antigos ficam intactos, que rodar de novo nao muda nada
 * e que o Flyway valida.
 *
 * Usa um banco descartavel proprio (taxify_qa_migr2) do MySQL local - criado e apagado pelo teste; so com
 * DB_HOST=localhost. Nunca toca o banco da aplicacao.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
class PasswordAlertsMigrationsOnExistingDataTest {

    private static final String SCRATCH = "taxify_qa_migr2";
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

    private Map<String, Object> column(JdbcTemplate jdbc, String table, String column) {
        return jdbc.queryForMap("SELECT column_default, is_nullable, column_type FROM information_schema.columns"
                + " WHERE table_schema = ? AND table_name = ? AND column_name = ?", SCRATCH, table, column);
    }

    @Test
    void v71ToV75KeepExistingRowsAndSetTheDefaults() {
        // 1. the schema as it was before V71
        flyway("70").migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = 'users'",
                String.class, SCRATCH)).doesNotContain("session_version", "password_changed_at", "password_fail_count");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name IN"
                + " ('password_reset_tokens','user_notification_settings','pendency_alert_digests','pendency_alert_items')", Integer.class, SCRATCH)).isZero();

        // 2. rows written by the old version
        Timestamp created = Timestamp.valueOf(LocalDateTime.of(2026, 11, 20, 9, 0));
        String firmId = UUID.randomUUID().toString();
        String companyId = UUID.randomUUID().toString();
        String adminId = UUID.randomUUID().toString();
        String analystId = UUID.randomUUID().toString();
        String pendencyId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', 'Escritorio Exemplo Migracao')", firmId, created);
        for (String[] row : new String[][]{{UUID.randomUUID().toString(), "52998224725", "CPF"}, {UUID.randomUUID().toString(), "11144477735", "CPF"},
                {UUID.randomUUID().toString(), "11222333000181", "CNPJ"}}) {
            jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, ?)", row[0], created, row[1], row[2]);
        }
        List<String> documents = jdbc.queryForList("SELECT id FROM documents ORDER BY number", String.class);
        String hash = "$2a$10$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234";
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login, name, email, password)"
                + " VALUES (?, 0, ?, 'qa', 'ADMIN', ?, ?, 'carla.admin.migr', 'Carla Fictícia', 'carla@exemplo.test', ?)", adminId, created, firmId, documents.get(1), hash);
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login, name, email, password)"
                + " VALUES (?, 0, ?, 'qa', 'EMPLOYEE', ?, ?, 'ana.fiscal.migr', 'Ana Fictícia', 'ana@exemplo.test', ?)", analystId, created, firmId, documents.get(0), hash);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active) VALUES (?, 0, ?, 'qa', ?, ?, 'Têxtil Exemplo Ltda', 1)",
                companyId, created, firmId, documents.get(2));
        jdbc.update("INSERT INTO pendencies (id, company_id, type, severity, natural_key, title, status, first_seen_at, last_seen_at, auto_closed, deleted, created_by, created_date)"
                + " VALUES (?, ?, 'CERTIFICATE_EXPIRED', 'ALTA', 'CERTIFICATE_EXPIRED:x', 'titulo', 'ABERTA', ?, ?, 0, 0, 'qa', ?)", pendencyId, companyId, created, created, created);

        // 3. V71-V75
        assertThat(flyway("latest").migrate().migrationsExecuted).isGreaterThanOrEqualTo(5);

        for (String id : List.of(adminId, analystId)) {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);
            assertThat(((Number) row.get("session_version")).intValue()).as("old token without sv = 0 keeps working").isZero();
            assertThat(((Number) row.get("password_fail_count")).intValue()).isZero();
            assertThat(row.get("password_changed_at")).isNull();
            assertThat(row.get("password_fail_window_start")).isNull();
            assertThat(row.get("password")).isEqualTo(hash);
            assertThat(row.get("email")).isIn("carla@exemplo.test", "ana@exemplo.test");
        }
        assertThat(column(jdbc, "users", "session_version")).containsEntry("IS_NULLABLE", "NO").containsEntry("COLUMN_DEFAULT", "0");
        assertThat(column(jdbc, "users", "password_fail_count")).containsEntry("IS_NULLABLE", "NO").containsEntry("COLUMN_DEFAULT", "0");
        assertThat(column(jdbc, "users", "password_changed_at")).containsEntry("IS_NULLABLE", "YES");
        assertThat(column(jdbc, "users", "password_fail_window_start")).containsEntry("IS_NULLABLE", "YES");
        // a user inserted by the OLD application version (no new columns) during a rolling deploy still works
        String lateId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login, name, email, password)"
                + " VALUES (?, 0, ?, 'qa', 'EMPLOYEE', ?, ?, 'tarde.migr', 'Tarde', 'tarde@exemplo.test', ?)", lateId, created, firmId, documents.get(2), hash);
        assertThat(jdbc.queryForObject("SELECT session_version FROM users WHERE id = ?", Integer.class, lateId)).isZero();

        // untouched old data
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendencies WHERE id = ? AND severity = 'ALTA' AND status = 'ABERTA'", Integer.class, pendencyId)).isOne();
        assertThat(jdbc.queryForObject("SELECT name FROM companies WHERE id = ?", String.class, companyId)).isEqualTo("Têxtil Exemplo Ltda");

        // the new tables start empty: no notification row (default ON applies in code), no digest, no token
        for (String table : List.of("password_reset_tokens", "user_notification_settings", "pendency_alert_digests", "pendency_alert_items")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class)).as(table).isZero();
        }

        // 4. keys: token hash unique, one setting per user and type, one digest per user and day, one item per episode
        Timestamp now = Timestamp.valueOf(LocalDateTime.of(2026, 12, 1, 7, 50));
        String tokenSql = "INSERT INTO password_reset_tokens (id, user_id, token_hash, requested_at, expires_at, deleted, created_by, created_date)"
                + " VALUES (?, ?, ?, ?, ?, 0, 'qa', ?)";
        jdbc.update(tokenSql, UUID.randomUUID().toString(), analystId, "a".repeat(64), now, now, now);
        assertThatThrownBy(() -> jdbc.update(tokenSql, UUID.randomUUID().toString(), adminId, "a".repeat(64), now, now, now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(tokenSql, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "b".repeat(64), now, now, now))
                .as("FK to users").isInstanceOf(DataIntegrityViolationException.class);

        String settingSql = "INSERT INTO user_notification_settings (id, user_id, type, enabled, deleted, created_by, created_date) VALUES (?, ?, 'PENDENCY_HIGH_DIGEST', 1, 0, 'qa', ?)";
        jdbc.update(settingSql, UUID.randomUUID().toString(), analystId, now);
        assertThatThrownBy(() -> jdbc.update(settingSql, UUID.randomUUID().toString(), analystId, now)).isInstanceOf(DataIntegrityViolationException.class);

        String digestId = UUID.randomUUID().toString();
        String digestSql = "INSERT INTO pendency_alert_digests (id, accounting_firm_id, user_id, reference_date, status, deleted, created_by, created_date)"
                + " VALUES (?, ?, ?, '2026-12-01', 'SENDING', 0, 'qa', ?)";
        jdbc.update(digestSql, digestId, firmId, analystId, now);
        assertThatThrownBy(() -> jdbc.update(digestSql, UUID.randomUUID().toString(), firmId, analystId, now)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT companies_count + pendencies_count FROM pendency_alert_digests WHERE id = ?", Integer.class, digestId))
                .as("counts default 0").isZero();

        String itemSql = "INSERT INTO pendency_alert_items (id, digest_id, pendency_id, user_id, episode_started_at, deleted, created_by, created_date)"
                + " VALUES (?, ?, ?, ?, ?, 0, 'qa', ?)";
        jdbc.update(itemSql, UUID.randomUUID().toString(), digestId, pendencyId, analystId, created, now);
        assertThatThrownBy(() -> jdbc.update(itemSql, UUID.randomUUID().toString(), digestId, pendencyId, analystId, created, now))
                .as("same episode twice").isInstanceOf(DataIntegrityViolationException.class);
        // a new episode (first_seen_at moved) is a new row
        jdbc.update(itemSql, UUID.randomUUID().toString(), digestId, pendencyId, analystId, Timestamp.valueOf(LocalDateTime.of(2026, 11, 25, 9, 0)), now);
        assertThatThrownBy(() -> jdbc.update(itemSql, UUID.randomUUID().toString(), UUID.randomUUID().toString(), pendencyId, adminId, created, now))
                .as("FK to the digest").isInstanceOf(DataIntegrityViolationException.class);

        // 5. nothing more to do on a second run, and Flyway validates the history
        assertThat(flyway("latest").migrate().migrationsExecuted).isZero();
        flyway("latest").validate();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IN ('71','72','73','74','75') AND success = 1", String.class))
                .containsExactlyInAnyOrder("71", "72", "73", "74", "75");
    }
}
