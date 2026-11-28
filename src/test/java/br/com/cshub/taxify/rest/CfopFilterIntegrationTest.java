package br.com.cshub.taxify.rest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static br.com.cshub.taxify.rest.ReformItSupport.BASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Filtro de CFOP múltiplo (spec 2026-11-27) end to end on the local MySQL, with the fictitious scenario of section 12:
 * company A of firm E1 with the entries N1 to N5 and the CT-e C1 of 11/2026 (plus an exit O1), in the portfolio of
 * fiscal1; company A2 of E1 outside it; company B of firm E2; firm Z blocked. The list and the cards are compared for
 * every filter (risk R-01), the spec's numbers checked (CT-01 to CT-27), the 400s with their extra fields, the 404s of
 * the isolation and the Fronteira's {@code cfops}. Fictitious data, removed at the end.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.capture-priority.purge-enabled=false",
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false"
})
@AutoConfigureMockMvc
class CfopFilterIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 9, 0);
    private static final String LIST = BASE + "/integrations/xmls";
    private static final String TOTALS = LIST + "/totais";
    private static final String FRONTEIRA = BASE + "/fronteira";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private ReformItSupport it;
    private String a;
    private String a2;
    private String b;
    private String z;
    private String n3;
    private String n5;
    private String admin;
    private String fiscal1;
    private String adminE2;
    private String adminZ;

    @BeforeEach
    void setUp() throws Exception {
        it = new ReformItSupport(mvc, json, jdbc, passwordEncoder, NOW, "itcf");
        it.assumeLocalDatabase();

        String e1 = it.firm("Escritório Exemplo", "ACTIVE");
        a = it.company(e1, "Comércio Exemplo Ltda", "11222333000181", null);
        a2 = it.company(e1, "Outra Exemplo Ltda", ReformItSupport.randomCnpj(), null);
        LocalDateTime nov = LocalDateTime.of(2026, 11, 10, 10, 0);
        String n1 = note(a, "INBOUND", "AUTHORIZED", null, "1", "Fornecedor Alfa", "SIEG", nov, "1000.00", "120.00");
        item(n1, 1, "5102", "5102", "600.00", "72.00");
        item(n1, 2, "5405", "5405", "400.00", "48.00");
        source(n1, "SIEG");
        String n2 = note(a, "INBOUND", "AUTHORIZED", "REVIEWED", "2", "Fornecedor Beta", "UPLOAD", nov, "500.00", "60.00");
        item(n2, 1, "5102", "5102", "500.00", "60.00");
        source(n2, "SEFAZ_DFE");
        n3 = note(a, "INBOUND", "AUTHORIZED", null, "3", "Fornecedor Alfa", "SIEG", nov, "300.00", "36.00");
        item(n3, 1, "6102", "6102", "300.00", "36.00");
        bothSources(n3);
        String n4 = note(a, "INBOUND", "CANCELED", null, "4", "Fornecedor Beta", "SIEG", nov, "200.00", "24.00");
        item(n4, 1, "5102", "5102", "200.00", "24.00");
        bothSources(n4);
        n5 = note(a, "INBOUND", "AUTHORIZED", null, "5", "Fornecedor Gama", "SIEG", nov, "1050.00", "120.00");
        item(n5, 1, "5102", "5102", "1000.00", "120.00");
        bothSources(n5);
        String c1 = cte(a, "6", "Transportadora Delta", nov, "80.00");
        bothSources(c1);
        String o1 = note(a, "OUTBOUND", "AUTHORIZED", null, "7", "Comércio Exemplo Ltda", "SIEG", nov, "700.00", "84.00");
        item(o1, 1, "5102", "5102", "700.00", "84.00");
        // CT-27 (P-CF-02 = effective CFOP): issued 5102, rectified to 1102 - in October, away from the other cases
        String n7 = note(a, "INBOUND", "AUTHORIZED", null, "8", "Fornecedor Alfa", "SIEG", LocalDateTime.of(2026, 10, 10, 10, 0),
                "100.00", "12.00");
        item(n7, 1, "5102", "1102", "100.00", "12.00");
        note(a2, "INBOUND", "AUTHORIZED", null, "1", "Fornecedor Alfa", "SIEG", nov, "10.00", "1.20");

        String e2 = it.firm("Escritório Dois", "ACTIVE");
        b = it.company(e2, "Empresa Dois Ltda", ReformItSupport.randomCnpj(), null);
        String zFirm = it.firm("Escritório Bloqueado", "BLOCKED");
        z = it.company(zFirm, "Empresa Bloqueada Ltda", ReformItSupport.randomCnpj(), null);
        item(note(z, "INBOUND", "AUTHORIZED", null, "1", "Fornecedor Alfa", "SIEG", nov, "1000.00", "120.00"), 1, "5102", "5102",
                "1000.00", "120.00");

        it.user(e1, "admin", "ADMIN", null);
        String fiscal = it.user(e1, "fiscal1", "EMPLOYEE", "FISCAL");
        it.portfolio(fiscal, a);
        it.user(e2, "admin.e2", "ADMIN", null);
        it.user(zFirm, "admin.z", "ADMIN", null);
        admin = it.token("admin");
        fiscal1 = it.token("fiscal1");
        adminE2 = it.token("admin.e2");
        adminZ = it.token("admin.z");
    }

    @AfterEach
    void tearDown() {
        if (it != null) {
            it.removeAll();
        }
    }

    // ================================================================== list x cards (R-01)

    @Test
    void theCardsAddUpTheSameNotesTheListShowsForEveryFilter() throws Exception {
        jdbc.update("UPDATE fiscal_documents SET review_status = 'DIVERGENT' WHERE id IN (?, ?)", n3, n5);
        List<Map<String, Object>> filters = List.of(
                Map.of(),
                Map.of("cfops", List.of("5102")),
                Map.of("cfops", List.of("5.102", "6102", "5102")),
                Map.of("cfops", List.of("5102"), "participant", "alfa"),
                Map.of("cfops", List.of("5405")),
                Map.of("cfops", List.of("1102")),
                Map.of("reviewStatus", "REVIEWED"),
                Map.of("reviewStatus", "PENDING", "cfops", List.of("5102")),
                Map.of("numero", "0003"),
                Map.of("participant", "11222333000181"),
                Map.of("origem", "UPLOAD"),
                Map.of("origem", "nao existe!"),
                Map.of("fonte", "SO_SIEG"),
                Map.of("fonte", "SO_SEFAZ", "cfops", List.of("5102")),
                Map.of("divergencia", true),
                Map.of("divergencia", true, "cfops", List.of("6102")),
                Map.of("cfop", "5102"),
                Map.of("direction", "INBOUND", "cfops", List.of("5102")),
                Map.of("direction", "OUTBOUND"));
        for (Map<String, Object> filter : filters) {
            Map<String, Object> withoutTab = new LinkedHashMap<>(filter);
            withoutTab.remove("direction");
            List<JsonNode> rows = listAll(withoutTab, admin);
            JsonNode totals = it.ok(post(TOTALS).contentType(MediaType.APPLICATION_JSON).content(body(filter)), admin);
            for (String direction : List.of("INBOUND", "OUTBOUND")) {
                List<JsonNode> goods = rows.stream()
                        .filter(row -> direction.equals(row.get("direction").asText()) && !"CTE".equals(row.get("documentType").asText()))
                        .toList();
                List<JsonNode> counted = goods.stream().filter(row -> !"CANCELED".equals(row.get("status").asText())).toList();
                BigDecimal sum = counted.stream().map(row -> row.get("valorTotalNota").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
                JsonNode card = totals.get(direction.equals("INBOUND") ? "inbound" : "outbound");
                assertThat(card.get("count").asLong()).as(filter + " " + direction + " count").isEqualTo(counted.size());
                assertThat(card.get("total").decimalValue()).as(filter + " " + direction + " total").isEqualByComparingTo(sum);
                assertThat(card.get("canceledCount").asLong()).as(filter + " " + direction + " canceled")
                        .isEqualTo(goods.size() - counted.size());
            }
            boolean narrowed = withoutTab.keySet().stream().anyMatch(key -> !key.equals("companyId"));
            assertThat(totals.get("filtered").asBoolean()).as(filter + " filtered").isEqualTo(narrowed);
        }
    }

    // ================================================================== the spec's numbers

    /** CT-01 and CT-10. */
    @Test
    void withoutAFilterTheMonthAndWith5102TheWholeNotesThatHaveIt() throws Exception {
        JsonNode month = totals(Map.of(), admin);
        assertCard(month.get("inbound"), 4, "2850.00", "336.00", 1);
        assertThat(month.get("filtered").asBoolean()).isFalse();
        assertThat(month.get("cfopItems").isNull()).isTrue();
        assertThat(numbers(Map.of())).containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6", "7");

        JsonNode cut = totals(Map.of("cfops", List.of("5102")), admin);
        assertCard(cut.get("inbound"), 3, "2550.00", "300.00", 1);
        assertThat(cut.get("filtered").asBoolean()).isTrue();
        assertItems(cut.get("cfopItems").get("inbound"), 3, "2100.00", "252.00");
        assertItems(cut.get("cfopItems").get("outbound"), 1, "700.00", "84.00");
        assertThat(numbers(Map.of("cfops", List.of("5102")))).containsExactlyInAnyOrder("1", "2", "4", "5", "7");
    }

    /** CT-11 to CT-14 and CT-16. */
    @Test
    void severalCfopsAndTheOtherFiltersTogether() throws Exception {
        JsonNode both = totals(Map.of("cfops", List.of("5.102", "6102", "5102")), admin);
        assertCard(both.get("inbound"), 4, "2850.00", "336.00", 1);
        assertItems(both.get("cfopItems").get("inbound"), 4, "2400.00", "288.00");

        JsonNode alfa = totals(Map.of("cfops", List.of("5102"), "participant", "alfa"), admin);
        assertCard(alfa.get("inbound"), 1, "1000.00", "120.00", 0);
        assertItems(alfa.get("cfopItems").get("inbound"), 1, "600.00", "72.00");
        assertThat(numbers(Map.of("cfops", List.of("5102"), "participant", "alfa"))).containsExactly("1");

        JsonNode st = totals(Map.of("cfops", List.of("5405")), admin);
        assertCard(st.get("inbound"), 1, "1000.00", "120.00", 0);
        assertItems(st.get("cfopItems").get("inbound"), 1, "400.00", "48.00");

        JsonNode reviewed = totals(Map.of("reviewStatus", "REVIEWED"), admin);
        assertCard(reviewed.get("inbound"), 1, "500.00", "60.00", 0);
        assertThat(reviewed.get("filtered").asBoolean()).isTrue();
        assertThat(reviewed.get("cfopItems").isNull()).isTrue();

        JsonNode none = totals(Map.of("cfops", List.of("1102")), admin);
        assertCard(none.get("inbound"), 0, "0", "0", 0);
        assertCard(none.get("outbound"), 0, "0", "0", 0);
        assertThat(none.get("filtered").asBoolean()).isTrue();
        assertThat(numbers(Map.of("cfops", List.of("1102")))).isEmpty();
    }

    /** CT-15: only the divergent notes, summed in memory. */
    @Test
    void onlyTheDivergentNotes() throws Exception {
        jdbc.update("UPDATE fiscal_documents SET review_status = 'DIVERGENT' WHERE id IN (?, ?)", n3, n5);

        JsonNode divergent = totals(Map.of("divergencia", true), admin);

        assertCard(divergent.get("inbound"), 2, "1350.00", "156.00", 0);
        assertThat(numbers(Map.of("divergencia", true))).containsExactlyInAnyOrder("3", "5");
        JsonNode with5102 = totals(Map.of("divergencia", true, "cfops", List.of("5102")), admin);
        assertCard(with5102.get("inbound"), 1, "1050.00", "120.00", 0);
        assertItems(with5102.get("cfopItems").get("inbound"), 1, "1000.00", "120.00");
    }

    /** CT-17: the tab does not filter the cards. */
    @Test
    void theTabDoesNotFilterTheCards() throws Exception {
        JsonNode withTab = totals(Map.of("direction", "INBOUND", "cfops", List.of("5102")), admin);
        JsonNode withoutTab = totals(Map.of("cfops", List.of("5102")), admin);

        assertThat(withTab).isEqualTo(withoutTab);
        assertThat(numbers(Map.of("direction", "INBOUND", "cfops", List.of("5102")))).containsExactlyInAnyOrder("1", "2", "4", "5");
    }

    /** CT-18 to CT-20 (CF-RN-09): the old field, never a 400. */
    @Test
    void theOldSingleCfopStillWorks() throws Exception {
        assertThat(totals(Map.of("cfop", "5102"), admin)).isEqualTo(totals(Map.of("cfops", List.of("5102")), admin));
        assertThat(numbers(Map.of("cfop", "51"))).isEmpty();
        assertThat(totals(Map.of("cfop", "51"), admin).get("inbound").get("count").asLong()).isZero();
        assertThat(totals(Map.of("cfop", "6102", "cfops", List.of("5102")), admin))
                .isEqualTo(totals(Map.of("cfops", List.of("5102")), admin));
        assertThat(numbers(Map.of("cfop", "6102", "cfops", List.of("5102")))).containsExactlyInAnyOrder("1", "2", "4", "5", "7");
    }

    /** CT-26 (Recife time) and CT-27 (the effective CFOP, P-CF-02 decided on 2026-11-28). */
    @Test
    void thePeriodIsOfRecifeAndTheEffectiveCfopIsCompared() throws Exception {
        String n6 = note(a, "INBOUND", "AUTHORIZED", null, "9", "Fornecedor Alfa", "SIEG", LocalDateTime.of(2026, 11, 30, 23, 30),
                "10.00", "1.20");
        item(n6, 1, "5102", "5102", "10.00", "1.20");
        assertCard(totals(Map.of("cfops", List.of("5102")), admin).get("inbound"), 4, "2560.00", "301.20", 1);
        Map<String, Object> december = new LinkedHashMap<>(Map.of("cfops", List.of("5102")));
        december.put("dataEmissaoInicio", "2026-12-01");
        december.put("dataEmissaoFim", "2026-12-31");
        assertThat(totals(december, admin).get("inbound").get("count").asLong()).isZero();

        Map<String, Object> october = new LinkedHashMap<>();
        october.put("dataEmissaoInicio", "2026-10-01");
        october.put("dataEmissaoFim", "2026-10-31");
        october.put("cfops", List.of("1102"));
        assertThat(numbers(october)).containsExactly("8");
        assertCard(totals(october, admin).get("inbound"), 1, "100.00", "12.00", 0);
        october.put("cfops", List.of("5102"));
        assertThat(numbers(october)).as("the issued CFOP is not the one in effect").isEmpty();
    }

    // ================================================================== errors

    /** CT-21, CT-22, CA-29 and CA-30, on both routes. */
    @Test
    void aBrokenCfopListIsA400WithTheExtraFieldsAtTheTop() throws Exception {
        for (String route : List.of(LIST, TOTALS)) {
            MvcResult invalid = it.call(post(route).contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("cfops", List.of("1102", "11", ""))))
                    .header("Accept-Language", "pt-BR"), admin);
            assertThat(ReformItSupport.status(invalid)).isEqualTo(400);
            JsonNode error = it.body(invalid);
            assertThat(error.get("code").asText()).isEqualTo("error.cfopFilter.invalid");
            assertThat(error.get("status").asInt()).isEqualTo(400);
            assertThat(error.get("invalidValues")).hasSize(2);
            assertThat(error.get("invalidValues").get(0).asText()).isEqualTo("11");
            assertThat(error.get("invalidValues").get(1).asText()).isEmpty();
            assertThat(error.has("timestamp")).isTrue();
            assertThat(error.get("message").asText()).contains("4");

            List<String> tooMany = IntStream.rangeClosed(1101, 1121).mapToObj(String::valueOf).toList();
            MvcResult many = it.call(post(route).contentType(MediaType.APPLICATION_JSON).content(body(Map.of("cfops", tooMany))), admin);
            assertThat(ReformItSupport.status(many)).isEqualTo(400);
            assertThat(it.code(many)).isEqualTo("error.cfopFilter.tooMany");
            assertThat(it.body(many).get("maxItems").asInt()).isEqualTo(20);

            List<String> twentyDistinct = new ArrayList<>(IntStream.rangeClosed(1101, 1120).mapToObj(String::valueOf).toList());
            twentyDistinct.add("1.101");
            assertThat(ReformItSupport.status(it.call(post(route).contentType(MediaType.APPLICATION_JSON)
                    .content(body(Map.of("cfops", twentyDistinct))), admin))).isEqualTo(200);
        }
    }

    // ================================================================== access

    /** CT-23, CT-24 (the 404 of today, the filter never widens the scope) and CT-25 (blocked firm reads). */
    @Test
    void theFilterNeverWidensTheScope() throws Exception {
        for (String route : List.of(LIST, TOTALS)) {
            for (String[] call : List.of(new String[]{fiscal1, a2}, new String[]{fiscal1, b}, new String[]{adminE2, a})) {
                MvcResult plain = it.call(post(route).contentType(MediaType.APPLICATION_JSON).content(body(call[1], Map.of())), call[0]);
                MvcResult filtered = it.call(post(route).contentType(MediaType.APPLICATION_JSON)
                        .content(body(call[1], Map.of("cfops", List.of("5102")))), call[0]);
                assertThat(ReformItSupport.status(filtered)).isEqualTo(404);
                assertThat(it.code(filtered)).isEqualTo(it.code(plain));
            }
        }
        assertCard(it.ok(post(TOTALS).contentType(MediaType.APPLICATION_JSON).content(body(a, Map.of("cfops", List.of("5102")))),
                fiscal1).get("inbound"), 3, "2550.00", "300.00", 1);
        JsonNode blocked = it.ok(post(TOTALS).contentType(MediaType.APPLICATION_JSON)
                .content(body(z, Map.of("cfops", List.of("5102")))), adminZ);
        assertCard(blocked.get("inbound"), 1, "1000.00", "120.00", 0);
        assertThat(it.ok(post(LIST).contentType(MediaType.APPLICATION_JSON).content(body(z, Map.of("cfops", List.of("5102")))), adminZ)
                .get("content")).hasSize(1);
    }

    // ================================================================== Fronteira

    /** CF-RN-20 and CT-34 to CT-37: the parameter in both forms, the 400 and the 404. */
    @Test
    void theFronteiraTakesTheCfopsAsAListParameter() throws Exception {
        JsonNode without = it.ok(get(FRONTEIRA).param("companyId", a).param("periodo", "2026-11"), admin);
        assertThat(without.get("cfopFilter").isNull()).isTrue();

        JsonNode comma = it.ok(get(FRONTEIRA).param("companyId", a).param("periodo", "2026-11").param("cfops", "6403,6.102"), admin);
        JsonNode repeated = it.ok(get(FRONTEIRA).param("companyId", a).param("periodo", "2026-11")
                .param("cfops", "6102").param("cfops", "6403"), admin);
        assertThat(comma.get("cfopFilter")).isEqualTo(repeated.get("cfopFilter"));
        JsonNode mixed = it.ok(get(FRONTEIRA).param("companyId", a).param("periodo", "2026-11")
                .param("cfops", "6102,6403").param("cfops", "6949"), admin);
        assertThat(mixed.get("cfopFilter").get("cfops").toString()).isEqualTo("[\"6102\",\"6403\",\"6949\"]");
        assertThat(comma.get("cfopFilter").get("cfops").toString()).isEqualTo("[\"6102\",\"6403\"]");
        assertThat(comma.get("cfopFilter").get("matchingLines")).isEmpty(); // no extract this month (CT-34)
        assertThat(comma.get("cfopFilter").get("totals").get("notes").asInt()).isZero();
        assertThat(comma.get("lines")).isEqualTo(without.get("lines"));

        MvcResult invalid = it.call(get(FRONTEIRA).param("companyId", a).param("periodo", "2026-11").param("cfops", "61"), admin);
        assertThat(ReformItSupport.status(invalid)).isEqualTo(400);
        assertThat(it.code(invalid)).isEqualTo("error.cfopFilter.invalid");
        assertThat(it.body(invalid).get("invalidValues").get(0).asText()).isEqualTo("61");

        MvcResult outside = it.call(get(FRONTEIRA).param("companyId", a).param("periodo", "2026-11").param("cfops", "6102"), adminE2);
        assertThat(ReformItSupport.status(outside)).isEqualTo(404);
    }

    // ================================================================== helpers

    private String body(Map<String, Object> filter) throws Exception {
        return body(a, filter);
    }

    private String body(String company, Map<String, Object> filter) throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("companyId", company);
        fields.put("dataEmissaoInicio", "2026-11-01");
        fields.put("dataEmissaoFim", "2026-11-30");
        fields.putAll(filter);
        fields.put("pagination", Map.of("page", 0, "size", 100));
        return json.writeValueAsString(fields);
    }

    private JsonNode totals(Map<String, Object> filter, String token) throws Exception {
        return it.ok(post(TOTALS).contentType(MediaType.APPLICATION_JSON).content(body(filter)), token);
    }

    private List<JsonNode> listAll(Map<String, Object> filter, String token) throws Exception {
        JsonNode page = it.ok(post(LIST).contentType(MediaType.APPLICATION_JSON).content(body(filter)), token);
        assertThat(page.get("totalElements").asLong()).isLessThanOrEqualTo(100);
        List<JsonNode> rows = new ArrayList<>();
        page.get("content").forEach(rows::add);
        return rows;
    }

    private List<String> numbers(Map<String, Object> filter) throws Exception {
        return listAll(filter, admin).stream().map(row -> row.get("numero").asText()).toList();
    }

    private static void assertCard(JsonNode card, long count, String total, String icms, long canceled) {
        assertThat(card.get("count").asLong()).as("count").isEqualTo(count);
        assertThat(card.get("total").decimalValue()).as("total").isEqualByComparingTo(total);
        assertThat(card.get("icms").decimalValue()).as("icms").isEqualByComparingTo(icms);
        assertThat(card.get("canceledCount").asLong()).as("canceledCount").isEqualTo(canceled);
    }

    private static void assertItems(JsonNode items, long count, String amount, String icms) {
        assertThat(items.get("itemCount").asLong()).as("itemCount").isEqualTo(count);
        assertThat(items.get("itemsAmount").decimalValue()).as("itemsAmount").isEqualByComparingTo(amount);
        assertThat(items.get("icms").decimalValue()).as("icms of the items").isEqualByComparingTo(icms);
        assertThat(items.get("icmsSt").decimalValue()).isEqualByComparingTo("0");
    }

    private String note(String company, String direction, String status, String review, String number, String issuer,
                        String source, LocalDateTime issuedAt, String total, String icms) {
        String id = UUID.randomUUID().toString();
        boolean outbound = "OUTBOUND".equals(direction);
        jdbc.update("INSERT INTO fiscal_documents (id, deleted, created_date, created_by, document_type, model, source_system, company_id,"
                        + " xml_file_path, direction, company_role, status, issue_date, metadata_version, number, issuer_name,"
                        + " issuer_document, issuer_document_type, recipient_name, recipient_document, recipient_document_type,"
                        + " review_status, total_amount, icms_amount, icms_st_amount, ipi_amount, pis_amount, cofins_amount)"
                        + " VALUES (?, 0, ?, 'it', 'NFE', '55', ?, ?, 'xml/ficticio.xml', ?, ?, ?, ?, 0, ?, ?, ?, 'CNPJ',"
                        + " 'Comércio Exemplo Ltda', '11222333000181', 'CNPJ', ?, ?, ?, 0, 0, 0, 0)",
                id, Timestamp.valueOf(NOW), source, company, direction, outbound ? "ISSUER" : "RECIPIENT", status,
                Timestamp.valueOf(issuedAt), number, issuer, outbound ? "11222333000181" : "12345678000195", review,
                new BigDecimal(total), new BigDecimal(icms));
        return id;
    }

    private String cte(String company, String number, String issuer, LocalDateTime issuedAt, String total) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO fiscal_documents (id, deleted, created_date, created_by, document_type, model, source_system, company_id,"
                        + " xml_file_path, direction, company_role, status, issue_date, metadata_version, number, issuer_name,"
                        + " issuer_document, issuer_document_type, total_amount)"
                        + " VALUES (?, 0, ?, 'it', 'CTE', '57', 'SIEG', ?, 'xml/cte.xml', 'INBOUND', 'RECIPIENT', 'AUTHORIZED', ?, 0, ?, ?,"
                        + " '33444555000181', 'CNPJ', ?)",
                id, Timestamp.valueOf(NOW), company, Timestamp.valueOf(issuedAt), number, issuer, new BigDecimal(total));
        return id;
    }

    private void item(String document, int number, String cfop, String effectiveCfop, String amount, String icms) {
        jdbc.update("INSERT INTO fiscal_document_items (id, document_id, item_number, product_code, cfop, effective_cfop, total_amount,"
                        + " icms_amount, icms_st_amount, ipi_amount, pis_amount, cofins_amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0)",
                UUID.randomUUID().toString(), document, number, "P-" + number, cfop, effectiveCfop, new BigDecimal(amount),
                new BigDecimal(icms));
    }

    private void source(String document, String source) {
        String company = jdbc.queryForObject("SELECT company_id FROM fiscal_documents WHERE id = ?", String.class, document);
        Timestamp seen = Timestamp.valueOf(LocalDateTime.of(2026, 11, 2, 8, 0));
        jdbc.update("INSERT INTO fiscal_document_sources (id, document_id, company_id, source, retrieval_mode, first_seen_at, last_seen_at)"
                + " VALUES (?, ?, ?, ?, 'PERIOD', ?, ?)", UUID.randomUUID().toString(), document, company, source, seen, seen);
    }

    private void bothSources(String document) {
        source(document, "SIEG");
        source(document, "SEFAZ_DFE");
    }
}
