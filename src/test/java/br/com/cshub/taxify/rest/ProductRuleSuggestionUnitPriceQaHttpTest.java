package br.com.cshub.taxify.rest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA of the unit price on the rule suggestions (spec 2026-11-28, Q-PU-01) over real HTTP (random port, java.net.http):
 * CT-01 to CT-10 with the exact numbers of the spec on the JSON, CT-11 isolation (other firm 404, FISCAL out of the
 * portfolio 404, no token 401, profile without the permission 403), CT-12 blocked firm still reads, CT-13/CA-08 the
 * same number of statements with 3, 300 and 600 products (the 500 limit), CT-14 the rule form's filter, and no
 * sensitive data in the logs. Fictitious data (@exemplo.test, invented CNPJ), deleted at the end. Only with DB_HOST=localhost.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false"
})
class ProductRuleSuggestionUnitPriceQaHttpTest {

    private static final String PASSWORD = "SenhaDeTeste2026";
    private static final String SUP_A = "11222333000181";
    private static final String SUP_B = "11444777000161";
    private static final Random RANDOM = new Random();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 10, 0);

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> firms = new ArrayList<>();
    private final String prefix = "qapu" + Integer.toHexString(RANDOM.nextInt(0xfffff)) + ".";
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    private String firmA;
    private String firmC;
    private String firmBlocked;
    private String companyX;
    private String companyOutOfPortfolio;
    private String companyC;
    private String companyBlocked;
    private int itemNumber;

    @BeforeEach
    void setUp() {
        String url = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(url != null && (url.startsWith("jdbc:mysql://localhost:") || url.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + url);
        logs.start();
        rootLogger.addAppender(logs);
        firmA = firm("Escritorio Alfa", "ACTIVE");
        firmC = firm("Escritorio Gama", "ACTIVE");
        firmBlocked = firm("Escritorio Bloqueado", "BLOCKED");
        companyX = company(firmA, "Empresa Ficticia X Ltda");
        companyOutOfPortfolio = company(firmA, "Empresa Ficticia Fora Ltda");
        companyC = company(firmC, "Empresa Ficticia Gama Ltda");
        companyBlocked = company(firmBlocked, "Empresa Ficticia Bloqueada Ltda");
        user(firmA, "carla.admin", "ADMIN", null);
        String ana = user(firmA, "ana.fiscal", "EMPLOYEE", "FISCAL");
        user(firmA, "davi.gestao", "EMPLOYEE", "MANAGEMENT");
        user(firmA, "fabio.financ", "EMPLOYEE", "FINANCIAL");
        user(firmC, "admin.gama", "ADMIN", null);
        user(firmBlocked, "admin.bloq", "ADMIN", null);
        jdbc.update("INSERT INTO users_companies (user_id, company_id, role, deleted, created_date, created_by)"
                + " VALUES (?, ?, 'MEMBER', 0, ?, 'qa')", ana, companyX, Timestamp.valueOf(NOW));
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        List<String> messages = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        String all = String.join("\n", messages).toLowerCase();
        for (String sensitive : List.of(SUP_A, SUP_B, "11.222.333", "fornecedor exemplo", "empresa ficticia", "parafuso", prefix.toLowerCase(),
                "@exemplo.test", PASSWORD.toLowerCase())) {
            assertThat(all).as("log sem dado sensivel: " + sensitive).doesNotContain(sensitive);
        }
        for (String firm : firms) {
            removeFirm(firm);
        }
        firms.clear();
    }

    @Test
    void ct01ToCt10ExactNumbersOverHttp() throws Exception {
        String noteA = note(companyX, SUP_A, "RECIPIENT", "AUTHORIZED", false);
        // CT-01 (+ CT-05 noise: canceled, deleted, issued by the company)
        item(noteA, "PRD-001", "UN", "10", "12");
        item(noteA, "PRD-001", "UN", "30", "14");
        item(noteA, "PRD-001", "UN", "60", "15.5");
        item(note(companyX, SUP_A, "RECIPIENT", "CANCELED", false), "PRD-001", "UN", "50", "99");
        item(note(companyX, SUP_A, "RECIPIENT", "AUTHORIZED", true), "PRD-001", "UN", "5", "1");
        item(note(companyX, SUP_A, "ISSUER", "AUTHORIZED", false), "PRD-001", "UN", "5", "200");
        // CT-07 B
        item(note(companyX, SUP_B, "RECIPIENT", "AUTHORIZED", false), "PRD-001", "UN", "4", "20");
        // CT-02
        item(noteA, "PRD-002", "UN", "1", "10");
        item(noteA, "PRD-002", "UN", "1", "10.0001");
        // CT-03
        item(noteA, "PRD-003", "un", "2", "5");
        item(noteA, "PRD-003", "UN ", "2", "5");
        item(noteA, "PRD-003", "UN", "1", "6.5");
        item(noteA, "PRD-003", "CX", "1", "60");
        // CT-04
        item(noteA, "PRD-004", "UN", "2", "3");
        item(noteA, "PRD-004", "UN", "3", "3");
        item(noteA, "PRD-004", "KG", "1.5", "4");
        item(noteA, "PRD-004", "KG", "1.5", "4");
        // CT-06
        item(noteA, "PRD-006", "UN", "0", "10");
        item(noteA, "PRD-006", "UN", "2", null);
        // CT-08: two notes of the same supplier (the column holds only digits - 14 chars; the masked join is in the unit test)
        item(note(companyX, SUP_A, "RECIPIENT", "AUTHORIZED", false), "PRD-008", "UN", "1", "8");
        item(noteA, "PRD-008", "UN", "3", "12");
        // CT-09
        item(noteA, "PRD-009", "", "1", "2");
        item(noteA, "PRD-009", null, "1", "4");
        // CT-10
        item(noteA, "PRD-010", "UN", "1000", "0.035");

        Resp r = get("/product-fiscal-rules/sugestoes?companyId=" + companyX + "&somenteSemRegra=false", token("carla.admin"));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        JsonNode list = r.body();
        // the wire format keeps the 4 places of the contract (14.7000, not 14.7)
        assertThat(r.raw()).contains("\"average\":14.7000").contains("\"min\":12.0000").contains("\"max\":15.5000")
                .contains("\"totalQuantity\":100.0000");

        JsonNode ct01 = find(list, "PRD-001", SUP_A);
        assertThat(ct01.get("ocorrencias").asInt()).isEqualTo(3);
        JsonNode p = ct01.get("unitPrice");
        assertThat(num(p, "average")).isEqualTo("14.7000");
        assertThat(num(p, "min")).isEqualTo("12.0000");
        assertThat(num(p, "max")).isEqualTo("15.5000");
        assertThat(p.get("unit").asText()).isEqualTo("UN");
        assertThat(p.get("itemsConsidered").asInt()).isEqualTo(3);
        assertThat(num(p, "totalQuantity")).isEqualTo("100.0000");
        assertThat(p.get("otherUnits").isArray()).isTrue();
        assertThat(p.get("otherUnits")).isEmpty();
        // the price is not part of campos (PU-RN-08)
        assertThat(ct01.get("campos").toString()).doesNotContainIgnoringCase("price").doesNotContain("14.7");

        JsonNode ct07 = find(list, "PRD-001", SUP_B);
        assertThat(num(ct07.get("unitPrice"), "average")).isEqualTo("20.0000");
        assertThat(ct07.get("unitPrice").get("itemsConsidered").asInt()).isEqualTo(1);
        assertThat(num(ct07.get("unitPrice"), "min")).isEqualTo(num(ct07.get("unitPrice"), "max"));

        assertThat(num(find(list, "PRD-002", SUP_A).get("unitPrice"), "average")).isEqualTo("10.0001");

        JsonNode ct03 = find(list, "PRD-003", SUP_A).get("unitPrice");
        assertThat(num(ct03, "average")).isEqualTo("5.3000");
        assertThat(num(ct03, "min")).isEqualTo("5.0000");
        assertThat(num(ct03, "max")).isEqualTo("6.5000");
        assertThat(ct03.get("unit").asText()).isEqualTo("UN");
        assertThat(ct03.get("itemsConsidered").asInt()).isEqualTo(3);
        assertThat(ct03.get("otherUnits").toString()).isEqualTo("[{\"unit\":\"CX\",\"itemsConsidered\":1}]");

        JsonNode ct04 = find(list, "PRD-004", SUP_A).get("unitPrice");
        assertThat(ct04.get("unit").asText()).isEqualTo("UN");
        assertThat(num(ct04, "average")).isEqualTo("3.0000");
        assertThat(num(ct04, "min")).isEqualTo(num(ct04, "max"));
        assertThat(ct04.get("otherUnits").toString()).isEqualTo("[{\"unit\":\"KG\",\"itemsConsidered\":2}]");

        JsonNode ct06 = find(list, "PRD-006", SUP_A);
        assertThat(ct06.get("unitPrice").isNull()).isTrue();
        assertThat(ct06.get("ocorrencias").asInt()).isEqualTo(2);

        JsonNode ct08 = find(list, "PRD-008", SUP_A);
        assertThat(ct08.get("ocorrencias").asInt()).isEqualTo(2);
        assertThat(num(ct08.get("unitPrice"), "average")).isEqualTo("11.0000");
        assertThat(num(ct08.get("unitPrice"), "min")).isEqualTo("8.0000");
        assertThat(num(ct08.get("unitPrice"), "max")).isEqualTo("12.0000");

        JsonNode ct09 = find(list, "PRD-009", SUP_A).get("unitPrice");
        assertThat(ct09.get("unit").asText()).isEqualTo("—");
        assertThat(num(ct09, "average")).isEqualTo("3.0000");

        JsonNode ct10 = find(list, "PRD-010", SUP_A).get("unitPrice");
        assertThat(num(ct10, "average")).isEqualTo("0.0350");

        // CT-14: the rule form's filter brings the same price
        Resp form = get("/product-fiscal-rules/sugestoes?companyId=" + companyX + "&somenteSemRegra=false&codigoProduto=PRD-001&fornecedor="
                + SUP_A, token("ana.fiscal"));
        assertThat(form.status()).isEqualTo(200);
        assertThat(form.body()).hasSize(1);
        assertThat(form.body().get(0).get("unitPrice")).isEqualTo(p);

        // identifiers with SQL / % / CRLF are only parameters: no 500, no price leak of the others
        for (String bad : List.of("%27%20OR%201%3D1%20--", "%25", "PRD-001%0D%0AX", "%5C")) {
            Resp bad1 = get("/product-fiscal-rules/sugestoes?companyId=" + companyX + "&somenteSemRegra=false&codigoProduto=" + bad,
                    token("carla.admin"));
            assertThat(bad1.status()).as(bad).isEqualTo(200);
            assertThat(bad1.body()).as(bad).isEmpty();
        }
    }

    @Test
    void ct11AccessAndIsolation() throws Exception {
        String note = note(companyX, SUP_A, "RECIPIENT", "AUTHORIZED", false);
        item(note, "PRD-001", "UN", "1", "1");
        String url = "/product-fiscal-rules/sugestoes?companyId=";
        assertThat(get(url + companyX, null).status()).isEqualTo(401);
        assertThat(get(url + companyX, "isto.nao.e.um.jwt").status()).isIn(401, 403);
        // other firm
        assertThat(get(url + companyX, token("admin.gama")).status()).isEqualTo(404);
        assertThat(get(url + companyC, token("carla.admin")).status()).isEqualTo(404);
        // FISCAL: own portfolio 200, out of it 404
        assertThat(get(url + companyX, token("ana.fiscal")).status()).isEqualTo(200);
        assertThat(get(url + companyOutOfPortfolio, token("ana.fiscal")).status()).isEqualTo(404);
        // MANAGEMENT has FISCAL_RULES_VIEW, FINANCIAL does not
        assertThat(get(url + companyX, token("fabio.financ")).status()).isEqualTo(403);
        // inexistent and malformed id
        assertThat(get(url + UUID.randomUUID(), token("carla.admin")).status()).isEqualTo(404);
        assertThat(get(url + "nao-e-uuid", token("carla.admin")).status()).isIn(400, 404);
        // the 404 body carries no price
        assertThat(get(url + companyX, token("admin.gama")).raw()).doesNotContain("unitPrice");
    }

    @Test
    void ct12BlockedFirmStillReadsThePrices() throws Exception {
        String note = note(companyBlocked, SUP_A, "RECIPIENT", "AUTHORIZED", false);
        item(note, "PRD-001", "UN", "2", "7.5");
        Resp r = get("/product-fiscal-rules/sugestoes?companyId=" + companyBlocked, token("admin.bloq"));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(num(r.body().get(0).get("unitPrice"), "average")).isEqualTo("7.5000");
    }

    @Test
    void ct13Ca08TheSameNumberOfStatementsWith3And300And600Products() throws Exception {
        String note = note(companyX, SUP_A, "RECIPIENT", "AUTHORIZED", false);
        String token = token("carla.admin");
        String path = "/product-fiscal-rules/sugestoes?companyId=" + companyX + "&somenteSemRegra=false";
        insertProducts(note, 0, 3);
        get(path, token); // warm up
        long with3 = statements(path, token, 3);
        insertProducts(note, 3, 300);
        long with300 = statements(path, token, 300);
        insertProducts(note, 300, 600);
        long with600 = statements(path, token, 500); // the limit of 500
        assertThat(with300).isEqualTo(with3);
        assertThat(with600).isEqualTo(with3);
        // CA-08: exactly 2 statements on the items per call
        Statistics statistics = statistics();
        statistics.clear();
        get(path, token);
        long onItems = java.util.Arrays.stream(statistics.getQueries()).filter(q -> q.contains("FiscalDocumentItem")).count();
        assertThat(onItems).isEqualTo(2);
    }

    private long statements(String path, String token, int expected) throws Exception {
        Statistics statistics = statistics();
        statistics.clear();
        Resp r = get(path, token);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).hasSize(expected);
        for (JsonNode s : r.body()) {
            assertThat(s.get("unitPrice").isNull()).isFalse();
        }
        return statistics.getPrepareStatementCount();
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    // ---- helpers

    private static String num(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asString()).setScale(4).toPlainString();
    }

    private static JsonNode find(JsonNode list, String code, String supplier) {
        for (JsonNode s : list) {
            if (code.equals(s.get("codigoProduto").asText()) && supplier.equals(s.get("documentoFornecedor").asText())) {
                return s;
            }
        }
        throw new AssertionError(code + "/" + supplier + " not in " + list);
    }

    record Resp(int status, String raw, ObjectMapper mapper) {
        JsonNode body() {
            return mapper.readTree(raw);
        }
    }

    private Resp get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/taxify-service/api/v1" + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(response.statusCode(), response.body(), json);
    }

    private String token(String login) throws Exception {
        String auth = Base64.getEncoder().encodeToString((prefix + login + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/taxify-service/api/v1/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"auth\":\"" + auth + "\"}")).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("login " + login + ": " + response.body()).isEqualTo(200);
        return json.readTree(response.body()).get("token").asText();
    }

    private void insertProducts(String note, int from, int to) {
        List<Object[]> batch = new ArrayList<>();
        for (int i = from; i < to; i++) {
            batch.add(new Object[]{UUID.randomUUID().toString(), note, ++itemNumber, "P-" + String.format("%04d", i)});
        }
        jdbc.batchUpdate("INSERT INTO fiscal_document_items (id, document_id, item_number, product_code, cfop, total_amount,"
                + " commercial_unit, commercial_quantity, commercial_unit_price) VALUES (?, ?, ?, ?, '5102', 1.00, 'UN', 1, 1)", batch);
    }

    private String note(String company, String supplier, String companyRole, String status, boolean deleted) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO fiscal_documents (id, deleted, created_date, created_by, document_type, model, source_system, company_id,"
                        + " xml_file_path, direction, company_role, status, metadata_version, issue_date, issuer_document,"
                        + " issuer_document_type, issuer_name, header_destination_type)"
                        + " VALUES (?, ?, ?, 'qa', 'NFE', '55', 'SIEG', ?, 'xml/ficticio.xml', 'INBOUND', ?, ?, 6, ?, ?, 'CNPJ',"
                        + " 'Fornecedor Exemplo Ltda', '1')",
                id, deleted, Timestamp.valueOf(NOW), company, companyRole, status, Timestamp.valueOf(NOW), supplier);
        return id;
    }

    private void item(String note, String code, String unit, String quantity, String unitPrice) {
        jdbc.update("INSERT INTO fiscal_document_items (id, document_id, item_number, product_code, description, cfop, total_amount,"
                        + " commercial_unit, commercial_quantity, commercial_unit_price) VALUES (?, ?, ?, ?, 'Parafuso ficticio', '5102', 1.00, ?, ?, ?)",
                UUID.randomUUID().toString(), note, ++itemNumber, code, unit, new BigDecimal(quantity),
                unitPrice == null ? null : new BigDecimal(unitPrice));
    }

    private String firm(String name, String status) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', ?)", id, now,
                name + " " + prefix);
        jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, migrated, source, deleted, created_by, created_date)"
                + " VALUES (?, ?, ?, 0, 'MANUAL', 0, 'qa', ?)", UUID.randomUUID().toString(), id, status, now);
        firms.add(id);
        return id;
    }

    private String company(String firm, String name) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')",
                document, now, ProductRuleSuggestionUnitPriceQaHttpTest.cnpj(digits(8), "0001"));
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active, uf)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, ?, 1, 'PE')", id, now, firm, document, name);
        return id;
    }

    private String user(String firm, String login, String profile, String accessProfile) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CPF')",
                document, now, digits(11));
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login,"
                        + " name, email, password) VALUES (?, 0, ?, 'qa', ?, ?, ?, ?, ?, ?, ?)",
                id, now, profile, firm, document, prefix + login, "Pessoa " + login + " Ficticia",
                login + "." + prefix + "@exemplo.test", passwordEncoder.encode(PASSWORD));
        if (accessProfile != null) {
            jdbc.update("INSERT INTO user_access_profiles (user_id, access_profile) VALUES (?, ?)", id, accessProfile);
        }
        return id;
    }

    private void removeFirm(String firm) {
        List<String> companies = jdbc.queryForList("SELECT id FROM companies WHERE accounting_firm_id = ?", String.class, firm);
        for (String company : companies) {
            jdbc.update("DELETE i FROM fiscal_document_items i JOIN fiscal_documents d ON d.id = i.document_id WHERE d.company_id = ?",
                    company);
            for (String table : List.of("fiscal_document_sources", "fiscal_documents", "subscriptions", "users_companies",
                    "product_fiscal_rules")) {
                jdbc.update("DELETE FROM " + table + " WHERE company_id = ?", company);
            }
        }
        List<Map<String, Object>> users = jdbc.queryForList("SELECT id, document_id FROM users WHERE accounting_firm_id = ?", firm);
        for (Map<String, Object> user : users) {
            for (String table : List.of("user_access_profiles", "users_companies", "password_reset_tokens", "user_notification_settings")) {
                jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", user.get("id"));
            }
        }
        List<String> companyDocuments = jdbc.queryForList("SELECT document_id FROM companies WHERE accounting_firm_id = ?", String.class, firm);
        jdbc.update("DELETE FROM companies WHERE accounting_firm_id = ?", firm);
        jdbc.update("DELETE FROM users WHERE accounting_firm_id = ?", firm);
        for (String document : companyDocuments) {
            jdbc.update("DELETE FROM documents WHERE id = ?", document);
        }
        for (Map<String, Object> user : users) {
            jdbc.update("DELETE FROM documents WHERE id = ?", user.get("document_id"));
        }
        jdbc.update("DELETE FROM firm_subscription_events WHERE accounting_firm_id = ?", firm);
        jdbc.update("DELETE FROM firm_subscriptions WHERE accounting_firm_id = ?", firm);
        jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firm);
    }

    static String cnpj(String root, String establishment) {
        String base = root + establishment;
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int d1 = check(base, w1);
        int d2 = check(base + d1, w2);
        return base + d1 + d2;
    }

    private static int check(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    private static String digits(int length) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length; i++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }

    @SuppressWarnings("unused")
    private static final LocalDate UNUSED = LocalDate.of(2026, 11, 28);
}
