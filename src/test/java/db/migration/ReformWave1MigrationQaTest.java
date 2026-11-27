package db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA das migrations da Onda 1 da Reforma (V80-V84) pelo caminho real do deploy: o Flyway (o mesmo que o Spring Boot
 * chama na subida), não a classe da migration chamada à mão. Banco descartável taxify_qa_mig_reform no MySQL local,
 * criado e apagado pelo teste; só com DB_HOST=localhost.
 *
 * <ul>
 *   <li>dados no esquema antigo (V79) - 2026 da V24 e um ano digitado à mão, uma linha com valor no limite das 4 casas -
 *       preservados, defaults e nulabilidade conferidos, e a segunda subida não refaz nada;</li>
 *   <li>lock de metadados preso em ibs_cbs_rates durante a subida: a V80 desiste em ~30 s com a mensagem clara, e a
 *       subida seguinte (lock livre) completa - conferindo o que o Flyway grava no histórico quando a V80 falha.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
class ReformWave1MigrationQaTest {

    private static final String SCRATCH = "taxify_qa_mig_reform";
    private String port;
    private String user;
    private String password;

    @BeforeEach
    void createScratchDatabase() {
        port = System.getenv().getOrDefault("DB_PORT", "3306");
        user = System.getenv().getOrDefault("DB_USER", "root");
        password = System.getenv().getOrDefault("DB_PASSWORD", "root");
        assertThat(System.getenv("DB_HOST")).isIn("localhost", "127.0.0.1");
        JdbcTemplate admin = jdbc("");
        admin.execute("DROP DATABASE IF EXISTS " + SCRATCH);
        admin.execute("CREATE DATABASE " + SCRATCH + " CHARACTER SET utf8mb4");
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
        String url = url(database);
        assertThat(url).startsWith("jdbc:mysql://localhost:");
        return new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    }

    /** Como a subida da aplicação (spring.flyway.*: baseline-on-migrate, baseline 0). */
    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(url(SCRATCH), user, password).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("0").target(target).load();
    }

    @Test
    void oldDataPreservedAndSecondStartChangesNothing() {
        flyway("79").migrate();
        JdbcTemplate jdbc = jdbc(SCRATCH);
        // esquema antigo: 2026 da V24 + um ano digitado à mão (números fictícios) com 4 casas no limite
        jdbc.update("INSERT INTO ibs_cbs_rates (ano, aliquota_cbs, aliquota_ibs_uf, aliquota_ibs_mun) VALUES (2028, 99.9999, 0.0001, 0)");
        Map<String, Object> before2026 = jdbc.queryForMap("SELECT aliquota_cbs, aliquota_ibs_uf, aliquota_ibs_mun FROM ibs_cbs_rates WHERE ano = 2026");

        flyway("84").migrate();

        Map<String, Object> y2026 = jdbc.queryForMap("SELECT * FROM ibs_cbs_rates WHERE ano = 2026");
        assertThat(y2026.get("status")).isEqualTo("LAW");
        assertThat(y2026.get("version")).isEqualTo(0);
        for (String column : List.of("aliquota_cbs", "aliquota_ibs_uf", "aliquota_ibs_mun")) {
            assertThat((BigDecimal) y2026.get(column)).as(column).isEqualByComparingTo((BigDecimal) before2026.get(column));
        }
        Map<String, Object> y2028 = jdbc.queryForMap("SELECT * FROM ibs_cbs_rates WHERE ano = 2028");
        assertThat(y2028.get("status")).as("ano digitado à mão fica fora do motor").isEqualTo("PENDING");
        assertThat((BigDecimal) y2028.get("aliquota_cbs")).isEqualByComparingTo("99.9999");
        assertThat((BigDecimal) y2028.get("aliquota_ibs_uf")).isEqualByComparingTo("0.0001");
        assertThat(y2028.get("legal_basis")).isNull();

        // nulabilidade e defaults das colunas novas
        Map<String, String> nullable = new java.util.HashMap<>();
        Map<String, String> defaults = new java.util.HashMap<>();
        jdbc.query("SELECT column_name, is_nullable, column_default FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'ibs_cbs_rates'", rs -> {
                    nullable.put(rs.getString(1).toLowerCase(), rs.getString(2));
                    defaults.put(rs.getString(1).toLowerCase(), rs.getString(3));
                }, SCRATCH);
        assertThat(nullable).containsEntry("aliquota_cbs", "YES").containsEntry("aliquota_ibs_uf", "YES")
                .containsEntry("aliquota_ibs_mun", "YES").containsEntry("version", "NO").containsEntry("deleted", "NO")
                .containsEntry("ano", "NO");
        assertThat(defaults.get("version")).isEqualTo("0");

        Map<String, String> surveyNullable = new java.util.HashMap<>();
        jdbc.query("SELECT column_name, is_nullable FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'stock_credit_surveys'", rs -> {
                    surveyNullable.put(rs.getString(1).toLowerCase(), rs.getString(2));
                }, SCRATCH);
        assertThat(surveyNullable).containsEntry("company_id", "NO").containsEntry("survey_year", "NO")
                .containsEntry("contact_status", "NO").containsEntry("version", "NO").containsEntry("calculated_at", "YES");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'stock_credit_surveys' AND column_name = 'purchases_total'", String.class, SCRATCH)).isEqualTo("decimal");
        assertThat(jdbc.queryForObject("SELECT numeric_scale FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'stock_credit_surveys' AND column_name = 'purchases_total'", Integer.class, SCRATCH)).isEqualTo(2);

        // segunda subida: nada pendente, nada muda
        jdbc.update("UPDATE ibs_cbs_rates SET legal_basis = 'Art. fictício do QA', version = 3 WHERE ano = 2026");
        int applied = flyway("84").migrate().migrationsExecuted;
        assertThat(applied).isZero();
        assertThat(jdbc.queryForObject("SELECT legal_basis FROM ibs_cbs_rates WHERE ano = 2026", String.class)).isEqualTo("Art. fictício do QA");
        assertThat(jdbc.queryForObject("SELECT version FROM ibs_cbs_rates WHERE ano = 2026", Integer.class)).isEqualTo(3);
        assertThat(flyway("84").validateWithResult().validationSuccessful).isTrue();
    }

    /**
     * Subida com uma transação longa segurando o lock de metadados de ibs_cbs_rates (a conferência lendo as alíquotas):
     * a V80 tem que desistir em ~30 s com a mensagem clara e a subida seguinte tem que completar.
     */
    @Test
    void busyTableFailsInThirtySecondsAndTheNextStartCompletes() throws Exception {
        flyway("79").migrate();
        Throwable failure = null;
        long elapsed;
        try (Connection holder = DriverManager.getConnection(url(SCRATCH), user, password)) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement(); ResultSet ignored = statement.executeQuery("SELECT * FROM ibs_cbs_rates")) {
                long started = System.currentTimeMillis();
                try {
                    flyway("84").migrate();
                } catch (RuntimeException e) {
                    failure = e;
                }
                elapsed = System.currentTimeMillis() - started;
                // enquanto a V80 esperava, uma leitura de outra sessão não pode ter ficado presa além do timeout
            } finally {
                holder.rollback();
            }
        }
        assertThat(failure).as("a subida falha em vez de travar").isNotNull();
        List<String> chain = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.add(String.valueOf(t.getMessage()));
        }
        assertThat(String.join(" | ", chain)).contains("ibs.cbs.rates.alter.failed");
        assertThat(elapsed).as("desiste em ~30 s").isBetween(25_000L, 90_000L);
        System.out.println("QA-MIG elapsed ms=" + elapsed);

        // o que ficou no histórico do Flyway depois da falha
        JdbcTemplate jdbc = jdbc(SCRATCH);
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version >= '80' ORDER BY installed_rank");
        System.out.println("QA-MIG history after failure=" + history);
        Map<String, Object> columns = jdbc.queryForMap("SELECT COUNT(*) AS n FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = 'ibs_cbs_rates' AND column_name = 'status'", SCRATCH);
        System.out.println("QA-MIG status column after failure=" + columns);

        // a próxima subida, com o lock livre, tem que completar sem intervenção manual
        Throwable second = null;
        try {
            flyway("84").migrate();
        } catch (RuntimeException e) {
            second = e;
            System.out.println("QA-MIG second start failed: " + e.getMessage());
        }
        assertThat(second).as("a subida seguinte completa sozinha (sem flyway repair)").isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM ibs_cbs_rates WHERE ano = 2026", String.class)).isEqualTo("LAW");
        MigrationInfo current = flyway("84").info().current();
        assertThat(current.getVersion().getVersion()).isEqualTo("84");
    }
}
