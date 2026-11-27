package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.config.StockSurveyProperties;
import br.com.cshub.taxify.domain.reform.stock.PurchaseCfops;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.reform.StockSurveyScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q-CE-3: desempenho do levantamento do crédito de estoque com massa fictícia grande (1.000 empresas, ~2 milhões de
 * itens de entrada de 2026) num banco descartável (DB_NAME=taxify_qa_perf_reform, criado antes e apagado depois pelo
 * QA). Mede a rodada inteira, o "Atualizar agora", as telas (p50/p95 por HTTP real), conta as consultas por digest
 * (performance_schema, filtrado pelo schema) e imprime o EXPLAIN das consultas da seção 8 sobre
 * fiscal_xml_document_items. Só roda com QA_PERF=1 e DB_HOST=localhost.
 */
@EnabledIfEnvironmentVariable(named = "QA_PERF", matches = "1")
@EnabledIfEnvironmentVariable(named = "DB_NAME", matches = "taxify_qa_perf_reform")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxify.reform.stock-survey.enabled=true", "taxify.reform.stock-survey.cron=-", "taxify.reform.stock-survey.retry-cron=-",
        "taxify.capture-priority.purge-enabled=false",
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false"
})
class ReformWave1QaPerfTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 10, 6, 15);
    private static final String FIRM = "00000000-0000-0000-0000-00000000fe01";
    private static final int COMPANIES = Integer.parseInt(System.getenv().getOrDefault("QA_PERF_COMPANIES", "1000"));
    private static final int NOTES = Integer.parseInt(System.getenv().getOrDefault("QA_PERF_NOTES", "200"));
    private static final int ITEMS = Integer.parseInt(System.getenv().getOrDefault("QA_PERF_ITEMS", "10"));

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        MutableClock qaPerfClock() {
            return new MutableClock(NOW);
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MutableClock clock;
    @Autowired
    private StockSurveyScheduler scheduler;
    @Autowired
    private StockSurveyProperties properties;

    private final HttpClient http = HttpClient.newHttpClient();
    private ReformItSupport it;

    @Test
    void largeMass() throws Exception {
        String url = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        assertThat(url).startsWith("jdbc:mysql://localhost:3306/taxify_qa_perf_reform");
        System.out.println("QA-PERF jdbc=" + url.replaceAll("\\?.*", ""));
        it = new ReformItSupport(null, json, jdbc, passwordEncoder, NOW, "qaperf");
        clock.set(NOW);
        properties.setEnabled(true);
        if (count("SELECT COUNT(*) FROM accounting_firms WHERE id = '" + FIRM + "'") == 0) {
            generate();
        }
        System.out.println("QA-PERF mass companies=" + count("SELECT COUNT(*) FROM companies")
                + " documents=" + count("SELECT COUNT(*) FROM fiscal_xml_documents")
                + " items=" + count("SELECT COUNT(*) FROM fiscal_xml_document_items")
                + " inbound2026Items=" + count("SELECT COUNT(*) FROM fiscal_xml_documents d JOIN fiscal_xml_document_items i ON i.document_id = d.id"
                + " WHERE d.direction = 'INBOUND' AND d.issue_date >= '2026-01-01' AND d.issue_date < '2027-01-01'"));
        jdbc.execute("ANALYZE TABLE fiscal_xml_documents, fiscal_xml_document_items, companies, company_tax_regimes");

        explains();

        // rodada inteira
        jdbc.update("DELETE FROM routine_runs");
        jdbc.update("DELETE FROM stock_credit_surveys");
        Map<String, Long> before = digests();
        long started = System.nanoTime();
        assertThat(scheduler.runOnce()).isPresent();
        long runMs = (System.nanoTime() - started) / 1_000_000;
        Map<String, Long> run = diff(before, digests());
        System.out.println("QA-PERF routine ms=" + runMs + " companies=" + COMPANIES + " batches=" + ((COMPANIES + 49) / 50));
        printDigests("routine", run);
        assertThat(count("SELECT COUNT(*) FROM stock_credit_surveys WHERE calculated_at IS NOT NULL")).isEqualTo(COMPANIES);

        // segunda rodada (dia seguinte): upsert sobre linhas existentes
        clock.set(NOW.plusDays(1));
        started = System.nanoTime();
        assertThat(scheduler.runOnce()).isPresent();
        System.out.println("QA-PERF routine2 ms=" + (System.nanoTime() - started) / 1_000_000);
        clock.set(NOW.plusDays(1).plusHours(3));

        String admin = token("admin");
        String fiscal = token("fiscal");
        List<String> ids = jdbc.queryForList("SELECT id FROM companies WHERE accounting_firm_id = ? ORDER BY id", String.class, FIRM);
        measure("list admin default", admin, "/reform/stock-credit?year=2026", 30);
        measure("list admin size200", admin, "/reform/stock-credit?year=2026&size=200", 30);
        measure("list admin page 19", admin, "/reform/stock-credit?year=2026&page=19", 30);
        measure("list admin filtered", admin, "/reform/stock-credit?year=2026&classification=HYPOTHESIS_I,HYPOTHESIS_II_III&coverage=PARTIAL&sort=yearPurchases,desc", 30);
        measure("list admin search", admin, "/reform/stock-credit?year=2026&search=Perf%20Empresa%2001", 30);
        measure("list fiscal (300 carteira)", fiscal, "/reform/stock-credit?year=2026", 30);
        measure("csv admin (1000 linhas)", admin, "/reform/stock-credit/export.csv?year=2026", 15);
        measure("detail", admin, "/reform/stock-credit/" + ids.get(7) + "?year=2026", 30);
        measure("history", admin, "/reform/stock-credit/" + ids.get(7) + "/history?year=2026", 30);

        // consultas por requisição: constante com o tamanho da página
        for (String path : List.of("/reform/stock-credit?year=2026&size=10", "/reform/stock-credit?year=2026&size=200")) {
            Map<String, Long> b = digests();
            assertThat(send(path, admin).statusCode()).isEqualTo(200);
            Map<String, Long> d = diff(b, digests());
            long total = d.values().stream().mapToLong(Long::longValue).sum();
            System.out.println("QA-PERF statements " + path + " total=" + total);
            printDigests(path, d);
        }

        // Atualizar agora (lote de 1)
        List<Long> refresh = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long t = System.nanoTime();
            HttpResponse<byte[]> response = sendPost("/reform/stock-credit/" + ids.get(100 + i) + "/refresh?year=2026", admin);
            refresh.add((System.nanoTime() - t) / 1_000_000);
            assertThat(response.statusCode()).isEqualTo(200);
        }
        report("refresh", refresh);
    }

    // ------------------------------------------------------------------ massa

    private void generate() {
        long t0 = System.currentTimeMillis();
        it.rawFirm(FIRM, "Escritorio Perf QA", "ACTIVE");
        it.user(FIRM, "admin", "ADMIN", null);
        String fiscal = it.user(FIRM, "fiscal", "EMPLOYEE", "FISCAL");
        jdbc.execute("DROP TABLE IF EXISTS qa_seq");
        jdbc.execute("CREATE TABLE qa_seq (n INT PRIMARY KEY)");
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            values.append(i == 0 ? "" : ",").append("(").append(i).append(")");
        }
        jdbc.execute("INSERT INTO qa_seq VALUES " + values);
        Timestamp at = Timestamp.valueOf(NOW);
        String[] regimes = {"LUCRO_PRESUMIDO", "LUCRO_REAL", "SIMPLES_NACIONAL", "MEI", null};
        List<Object[]> docs = new ArrayList<>();
        List<Object[]> companies = new ArrayList<>();
        List<Object[]> periods = new ArrayList<>();
        List<Object[]> portfolio = new ArrayList<>();
        for (int i = 0; i < COMPANIES; i++) {
            String id = UUID.randomUUID().toString();
            String doc = UUID.randomUUID().toString();
            docs.add(new Object[]{doc, at, ReformItSupport.cnpj(String.format("%08d", 10_000_000 + i), "0001")});
            companies.add(new Object[]{id, at, FIRM, doc, String.format("Perf Empresa %04d Ltda", i), regimes[i % 5]});
            if (i % 5 < 4) {
                periods.add(new Object[]{UUID.randomUUID().toString(), null, at, regimes[i % 5], id});
            }
            if (i % 7 == 0) {
                periods.add(new Object[]{UUID.randomUUID().toString(), java.sql.Date.valueOf("2027-01-01"), at, "SIMPLES_NACIONAL", id});
            }
            if (i < 300) {
                portfolio.add(new Object[]{fiscal, id, at});
            }
        }
        jdbc.batchUpdate("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')", docs);
        jdbc.batchUpdate("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active, uf,"
                + " tax_regime_type) VALUES (?, 0, ?, 'qa', ?, ?, ?, 1, 'PE', ?)", companies);
        jdbc.batchUpdate("INSERT INTO company_tax_regimes (id, deleted, valid_from, created_date, tax_regime, company_id, created_by)"
                + " VALUES (?, 0, ?, ?, ?, ?, 'qa')", periods);
        jdbc.batchUpdate("INSERT INTO users_companies (user_id, company_id, role, deleted, created_date, created_by)"
                + " VALUES (?, ?, 'MEMBER', 0, ?, 'qa')", portfolio);
        System.out.println("QA-PERF gen companies ms=" + (System.currentTimeMillis() - t0));

        // notas: NOTES de entrada 2026 por empresa (5% canceladas, 2% excluídas), 20 de entrada 2025 e 30 de saída 2026
        jdbc.update("INSERT INTO fiscal_xml_documents (id, deleted, created_date, created_by, document_type, source_system, company_id,"
                + " xml_file_path, direction, status, issue_date) SELECT UUID(), IF(s.n % 50 = 7, 1, 0), NOW(), 'qa', 'NFE', 'SIEG', c.id,"
                + " 'xml/perf.xml', 'INBOUND', IF(s.n % 20 = 3, 'CANCELED', 'AUTHORIZED'),"
                + " TIMESTAMP('2026-01-01') + INTERVAL ((s.n * 8760 DIV " + NOTES + ") + (CRC32(c.id) % 7)) HOUR"
                + " FROM companies c JOIN qa_seq s ON s.n < " + NOTES + " WHERE c.accounting_firm_id = ?", FIRM);
        jdbc.update("INSERT INTO fiscal_xml_documents (id, deleted, created_date, created_by, document_type, source_system, company_id,"
                + " xml_file_path, direction, status, issue_date) SELECT UUID(), 0, NOW(), 'qa', 'NFE', 'SIEG', c.id, 'xml/perf.xml',"
                + " IF(s.n < 20, 'INBOUND', 'OUTBOUND'), 'AUTHORIZED',"
                + " IF(s.n < 20, TIMESTAMP('2025-06-01') + INTERVAL s.n DAY, TIMESTAMP('2026-02-01') + INTERVAL s.n DAY)"
                + " FROM companies c JOIN qa_seq s ON s.n < 50 WHERE c.accounting_firm_id = ?", FIRM);
        System.out.println("QA-PERF gen documents ms=" + (System.currentTimeMillis() - t0));
        jdbc.update("INSERT INTO fiscal_xml_document_items (id, document_id, numero_item, cfop, valor_total, cst_pis, ncm)"
                + " SELECT UUID(), d.id, s.n + 1, ELT(1 + ((s.n + CRC32(d.id)) % 6), '5102', '1102', '6102', '5949', '5405', '1556'),"
                + " ROUND(((CRC32(CONCAT(d.id, s.n)) % 100000) / 100), 2), ELT(1 + (s.n % 4), '01', '04', '06', '50'),"
                + " CONCAT('2203', LPAD(s.n % 13, 4, '0')) FROM fiscal_xml_documents d JOIN qa_seq s ON s.n < " + ITEMS
                + " JOIN companies c ON c.id = d.company_id WHERE c.accounting_firm_id = ?", FIRM);
        System.out.println("QA-PERF gen items ms=" + (System.currentTimeMillis() - t0));
    }

    // ------------------------------------------------------------------ EXPLAIN

    private void explains() {
        List<String> batch = jdbc.queryForList("SELECT id FROM companies WHERE accounting_firm_id = ? ORDER BY id LIMIT 50", String.class, FIRM);
        String ids = batch.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
        String cfops = PurchaseCfops.codes().stream().map(c -> "'" + c + "'").collect(Collectors.joining(","));
        String base = " FROM fiscal_xml_documents d JOIN fiscal_xml_document_items i ON i.document_id = d.id"
                + " WHERE d.company_id IN (" + ids + ") AND d.direction = 'INBOUND' AND d.deleted = 0"
                + " AND (d.status IS NULL OR d.status <> 'CANCELED') AND d.issue_date >= '2026-01-01 00:00:00'"
                + " AND d.issue_date < '2027-01-01 00:00:00' AND i.cfop IN (" + cfops + ")";
        Map<String, String> queries = new java.util.LinkedHashMap<>();
        queries.put("monthly", "SELECT d.company_id, MONTH(d.issue_date) AS m, SUM(i.valor_total), COUNT(i.id), COUNT(DISTINCT d.id)" + base
                + " GROUP BY d.company_id, MONTH(d.issue_date)");
        queries.put("cst", "SELECT d.company_id, TRIM(i.cst_pis), SUM(i.valor_total), COUNT(i.id)" + base + " GROUP BY d.company_id, TRIM(i.cst_pis)");
        queries.put("ncm", "SELECT d.company_id, TRIM(i.ncm), SUM(i.valor_total), COUNT(i.id)" + base + " GROUP BY d.company_id, TRIM(i.ncm)");
        queries.put("coverage", "SELECT d.company_id, MONTH(d.issue_date), MIN(d.issue_date), MAX(d.issue_date) FROM fiscal_xml_documents d"
                + " WHERE d.company_id IN (" + ids + ") AND d.direction = 'INBOUND' AND d.deleted = 0"
                + " AND (d.status IS NULL OR d.status <> 'CANCELED') AND d.issue_date >= '2026-01-01' AND d.issue_date < '2027-01-01'"
                + " GROUP BY d.company_id, MONTH(d.issue_date)");
        queries.forEach((name, sql) -> {
            for (Map<String, Object> row : jdbc.queryForList("EXPLAIN " + sql)) {
                System.out.println("QA-PERF explain " + name + " table=" + row.get("table") + " type=" + row.get("type") + " key=" + row.get("key")
                        + " possible=" + row.get("possible_keys") + " rows=" + row.get("rows") + " filtered=" + row.get("filtered")
                        + " extra=" + row.get("Extra"));
            }
            long t = System.nanoTime();
            jdbc.queryForList(sql);
            System.out.println("QA-PERF query " + name + " batch50 ms=" + (System.nanoTime() - t) / 1_000_000);
        });
    }

    // ------------------------------------------------------------------ medição

    private void measure(String name, String token, String path, int times) throws Exception {
        assertThat(send(path, token).statusCode()).as(name).isEqualTo(200);
        List<Long> ms = new ArrayList<>();
        long bytes = 0;
        for (int i = 0; i < times; i++) {
            long t = System.nanoTime();
            HttpResponse<byte[]> response = send(path, token);
            ms.add((System.nanoTime() - t) / 1_000_000);
            assertThat(response.statusCode()).isEqualTo(200);
            bytes = response.body().length;
        }
        report(name + " (" + bytes + " bytes)", ms);
    }

    private static void report(String name, List<Long> ms) {
        List<Long> sorted = new ArrayList<>(ms);
        Collections.sort(sorted);
        long p50 = sorted.get((int) Math.ceil(sorted.size() * 0.50) - 1);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        System.out.println("QA-PERF http " + name + " n=" + sorted.size() + " p50=" + p50 + "ms p95=" + p95 + "ms max=" + sorted.getLast() + "ms");
    }

    private Map<String, Long> digests() {
        Map<String, Long> result = new HashMap<>();
        jdbc.query("SELECT LEFT(DIGEST_TEXT, 110) AS d, COUNT_STAR AS n FROM performance_schema.events_statements_summary_by_digest"
                + " WHERE SCHEMA_NAME = 'taxify_qa_perf_reform' AND DIGEST_TEXT NOT LIKE '%performance_schema%'", rs -> {
                    result.merge(rs.getString("d"), rs.getLong("n"), Long::sum);
                });
        return result;
    }

    private static Map<String, Long> diff(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> result = new HashMap<>();
        after.forEach((key, value) -> {
            long delta = value - before.getOrDefault(key, 0L);
            if (delta > 0) {
                result.put(key, delta);
            }
        });
        return result;
    }

    private static void printDigests(String name, Map<String, Long> digests) {
        digests.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> System.out.println("QA-PERF digest " + name + " n=" + e.getValue() + " " + e.getKey()));
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private String token(String login) throws Exception {
        String auth = Base64.getEncoder().encodeToString((it.login(login) + ":" + ReformItSupport.PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/auth"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"auth\":\"" + auth + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).get("token").asText();
    }

    private String base() {
        return "http://localhost:" + port + "/taxify-service/api/v1";
    }

    private HttpResponse<byte[]> send(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> sendPost(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
