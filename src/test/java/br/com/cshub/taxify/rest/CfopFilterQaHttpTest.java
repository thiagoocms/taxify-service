package br.com.cshub.taxify.rest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA do filtro de CFOP múltiplo (spec 2026-11-27, rev. 2) por HTTP real: servidor em porta aleatória, cliente
 * java.net.http, MySQL local. Complementa {@link CfopFilterIntegrationTest} (MockMvc, do dev) com: matriz aleatória de
 * filtros comparando lista x cards, bordas de período (fronteira exata do mês, micro-segundo), entradas ruins (SQL, %,
 * CRLF, unicode, JSON de tipo errado), perfis e escritórios, cliques simultâneos, a Fronteira (CT-30 a CT-37) com
 * extrato semeado, relatórios inalterados e um @AfterEach que varre os logs atrás de dado sensível.
 *
 * <p>Dados fictícios (@exemplo.test, CNPJ fictício válido), apagados no fim. Só roda com DB_HOST=localhost.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxify.platform.admin-logins=it.qacf.csadmin",
        "taxify.platform.firm-id=" + CfopFilterQaHttpTest.PLATFORM_FIRM,
        "taxify.capture-priority.purge-enabled=false",
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false"
})
class CfopFilterQaHttpTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-0000000c1f99";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 9, 0);
    private static final String LIST = "/integrations/xmls";
    private static final String TOTALS = LIST + "/totais";
    private static final String FRONTEIRA = "/fronteira";
    private static final String CNPJ_A = "11222333000181";

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final List<String> secrets = new CopyOnWriteArrayList<>();
    private ReformItSupport it;
    private String a;
    private String a2;
    private String b;
    private String z;
    private String f;
    private String n3;
    private String n5;
    private String e1;
    private String admin;
    private String fiscal1;
    private String adminE2;
    private String adminZ;
    private String management;
    private String financial;
    private String csadmin;
    private String uncharged1;
    private String uncharged2;

    record Resp(int status, String raw, byte[] bytes, ObjectMapper mapper) {
        JsonNode body() {
            return mapper.readTree(raw);
        }

        String code() {
            JsonNode body = body();
            return body.has("code") ? body.get("code").asText() : null;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        it = new ReformItSupport(null, json, jdbc, passwordEncoder, NOW, "qacf");
        String url = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        assertThat(url).as("JDBC URL").startsWith("jdbc:mysql://localhost:");
        System.out.println("QA-JDBC-URL " + url.replaceAll("\\?.*", ""));

        it.removeFirm(PLATFORM_FIRM);
        it.rawFirm(PLATFORM_FIRM, "CS Hub (teste QA)", null);
        it.user(PLATFORM_FIRM, "it.qacf.csadmin", "ADMIN", null);

        e1 = it.firm("Escritório Exemplo", "ACTIVE");
        a = it.company(e1, "Comércio Exemplo Ltda", CNPJ_A, null);
        a2 = it.company(e1, "Outra Exemplo Ltda", ReformItSupport.randomCnpj(), null);
        f = it.company(e1, "Fronteira Exemplo Ltda", ReformItSupport.randomCnpj(), null);
        LocalDateTime nov = LocalDateTime.of(2026, 11, 10, 10, 0);
        String n1 = note(a, "INBOUND", "AUTHORIZED", null, "1", "Fornecedor Alfa", "SIEG", nov, "1000.00", "120.00", "SP");
        item(n1, 1, "5102", "5102", "600.00", "72.00");
        item(n1, 2, "5405", "5405", "400.00", "48.00");
        source(n1, "SIEG");
        String n2 = note(a, "INBOUND", "AUTHORIZED", "REVIEWED", "2", "Fornecedor Beta", "UPLOAD", nov, "500.00", "60.00", "SP");
        item(n2, 1, "5102", "5102", "500.00", "60.00");
        source(n2, "SEFAZ_DFE");
        n3 = note(a, "INBOUND", "AUTHORIZED", null, "3", "Fornecedor Alfa", "SIEG", nov, "300.00", "36.00", "SP");
        item(n3, 1, "6102", "6102", "300.00", "36.00");
        bothSources(n3);
        String n4 = note(a, "INBOUND", "CANCELED", null, "4", "Fornecedor Beta", "SIEG", nov, "200.00", "24.00", "SP");
        item(n4, 1, "5102", "5102", "200.00", "24.00");
        bothSources(n4);
        n5 = note(a, "INBOUND", "AUTHORIZED", null, "5", "Fornecedor Gama", "SIEG", nov, "1050.00", "120.00", "SP");
        item(n5, 1, "5102", "5102", "1000.00", "120.00");
        bothSources(n5);
        bothSources(cte(a, "6", "Transportadora Delta", nov, "80.00"));
        String o1 = note(a, "OUTBOUND", "AUTHORIZED", null, "7", "Comércio Exemplo Ltda", "SIEG", nov, "700.00", "84.00", "PE");
        item(o1, 1, "5102", "5102", "700.00", "84.00");
        String n7 = note(a, "INBOUND", "AUTHORIZED", null, "8", "Fornecedor Alfa", "SIEG", LocalDateTime.of(2026, 10, 10, 10, 0),
                "100.00", "12.00", "SP");
        item(n7, 1, "5102", "1102", "100.00", "12.00");
        note(a2, "INBOUND", "AUTHORIZED", null, "1", "Fornecedor Alfa", "SIEG", nov, "10.00", "1.20", "SP");

        String e2 = it.firm("Escritório Dois", "ACTIVE");
        b = it.company(e2, "Empresa Dois Ltda", ReformItSupport.randomCnpj(), null);
        String zFirm = it.firm("Escritório Bloqueado", "BLOCKED");
        z = it.company(zFirm, "Empresa Bloqueada Ltda", ReformItSupport.randomCnpj(), null);
        item(note(z, "INBOUND", "AUTHORIZED", null, "1", "Fornecedor Alfa", "SIEG", nov, "1000.00", "120.00", "SP"), 1, "5102", "5102",
                "1000.00", "120.00");

        it.user(e1, "admin", "ADMIN", null);
        String fiscal = it.user(e1, "fiscal1", "EMPLOYEE", "FISCAL");
        it.portfolio(fiscal, a);
        it.portfolio(fiscal, f);
        String mgmt = it.user(e1, "gestao", "EMPLOYEE", "MANAGEMENT");
        it.portfolio(mgmt, a);
        it.user(e1, "financeiro", "EMPLOYEE", "FINANCIAL");
        it.user(e2, "admin.e2", "ADMIN", null);
        it.user(zFirm, "admin.z", "ADMIN", null);
        for (String login : List.of("admin", "fiscal1", "gestao", "financeiro", "admin.e2", "admin.z")) {
            secrets.add(it.login(login));
        }
        secrets.addAll(List.of(CNPJ_A, "Comércio Exemplo", "Fornecedor Alfa", "Fornecedor Beta", "Fornecedor Gama", "Transportadora Delta",
                "Empresa Dois", "Empresa Bloqueada", "Escritório Exemplo", "@exemplo.test", "Pessoa "));
        admin = token("admin");
        fiscal1 = token("fiscal1");
        management = token("gestao");
        financial = token("financeiro");
        adminE2 = token("admin.e2");
        adminZ = token("admin.z");
        csadmin = token("it.qacf.csadmin");
        seedFronteira();

        logs.list.clear();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        try {
            List<String> leaks = new ArrayList<>();
            for (ILoggingEvent event : logs.list) {
                String text = event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
                        : " " + event.getThrowableProxy().getMessage());
                for (String secret : secrets) {
                    if (secret != null && secret.length() >= 4 && text.contains(secret)) {
                        leaks.add(event.getLoggerName() + ": " + text);
                    }
                }
                if (text.contains("Bearer ") || text.matches("(?s).*eyJ[A-Za-z0-9_-]{10,}\\..*")) {
                    leaks.add(event.getLoggerName() + ": token " + text);
                }
            }
            assertThat(leaks).as("dado sensível em log").isEmpty();
        } finally {
            if (it != null) {
                for (String company : List.of(f)) {
                    if (company != null) {
                        jdbc.update("DELETE n FROM efisco_extract_notes n JOIN efisco_extracts e ON e.id = n.extract_id WHERE e.company_id = ?", company);
                        jdbc.update("DELETE FROM efisco_extracts WHERE company_id = ?", company);
                        jdbc.update("DELETE FROM fronteira_rules WHERE company_id = ?", company);
                    }
                }
                it.removeAll();
                it.removeFirm(PLATFORM_FIRM);
            }
        }
    }

    // ================================================================== 1. list x cards on random filter combinations (R-01)

    @Test
    void listAndCardsAgreeOnRandomFilterCombinations() throws Exception {
        jdbc.update("UPDATE fiscal_documents SET review_status = 'DIVERGENT' WHERE id IN (?, ?)", n3, n5);
        // more notes, so the combinations have something to cut: several participants, CFOPs, reviews, sources and days
        Random random = new Random(20261128);
        String[] issuers = {"Fornecedor Alfa", "Fornecedor Beta", "Fornecedor Gama", "Fornecedor Delta"};
        String[] cfops = {"5102", "5405", "6102", "6403", "5949", "1102"};
        String[] reviews = {null, "REVIEWED", "DIVERGENT"};
        String[] statuses = {"AUTHORIZED", "AUTHORIZED", "AUTHORIZED", "CANCELED"};
        for (int i = 0; i < 60; i++) {
            LocalDateTime when = LocalDateTime.of(2026, 11, 1 + random.nextInt(30), random.nextInt(24), random.nextInt(60));
            String direction = random.nextInt(4) == 0 ? "OUTBOUND" : "INBOUND";
            String doc = note(a, direction, statuses[random.nextInt(statuses.length)], reviews[random.nextInt(3)], String.valueOf(100 + i),
                    issuers[random.nextInt(issuers.length)], random.nextBoolean() ? "SIEG" : "UPLOAD", when,
                    (10 + random.nextInt(900)) + "." + (10 + random.nextInt(89)), "5.50", "SP");
            int items = 1 + random.nextInt(3);
            for (int k = 1; k <= items; k++) {
                String cfop = cfops[random.nextInt(cfops.length)];
                item(doc, k, cfop, cfop, (1 + random.nextInt(500)) + ".35", "1.17");
            }
            if (random.nextBoolean()) {
                bothSources(doc);
            } else {
                source(doc, random.nextBoolean() ? "SIEG" : "SEFAZ_DFE");
            }
        }
        List<Map<String, Object>> pool = List.of(
                Map.of("cfops", List.of("5102")), Map.of("cfops", List.of("5102", "6102")), Map.of("cfops", List.of("5.405", "6403", "5949")),
                Map.of("cfops", List.of("1102", "9999")), Map.of("participant", "alfa"), Map.of("participant", "Fornecedor"),
                Map.of("participant", CNPJ_A), Map.of("reviewStatus", "PENDING"), Map.of("reviewStatus", "REVIEWED"),
                Map.of("reviewStatus", "DIVERGENT"), Map.of("origem", "SIEG"), Map.of("origem", "UPLOAD"), Map.of("fonte", "SO_SIEG"),
                Map.of("fonte", "SO_SEFAZ"), Map.of("divergencia", true), Map.of("numero", "105"), Map.of("numero", "0007"),
                Map.of("direction", "INBOUND"), Map.of("direction", "OUTBOUND"),
                Map.of("dataEmissaoInicio", "2026-11-10", "dataEmissaoFim", "2026-11-20"));
        int checked = 0;
        for (int round = 0; round < 70; round++) {
            Map<String, Object> filter = new LinkedHashMap<>();
            int parts = random.nextInt(4);
            for (int p = 0; p < parts; p++) {
                filter.putAll(pool.get(random.nextInt(pool.size())));
            }
            Map<String, Object> withoutTab = new LinkedHashMap<>(filter);
            withoutTab.remove("direction");
            Resp list = post(LIST, admin, bodyOf(a, withoutTab, 100));
            Resp totals = post(TOTALS, admin, bodyOf(a, filter, 100));
            assertThat(list.status()).as(filter + " list").isEqualTo(200);
            assertThat(totals.status()).as(filter + " totals").isEqualTo(200);
            JsonNode page = list.body();
            assertThat(page.get("totalElements").asLong()).isLessThanOrEqualTo(100);
            for (String direction : List.of("INBOUND", "OUTBOUND")) {
                List<JsonNode> goods = new ArrayList<>();
                page.get("content").forEach(row -> {
                    if (direction.equals(row.get("direction").asText()) && !"CTE".equals(row.get("documentType").asText())) {
                        goods.add(row);
                    }
                });
                List<JsonNode> counted = goods.stream().filter(row -> !"CANCELED".equals(row.get("status").asText())).toList();
                BigDecimal sum = counted.stream().map(row -> row.get("valorTotalNota").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add);
                JsonNode card = totals.body().get(direction.equals("INBOUND") ? "inbound" : "outbound");
                assertThat(card.get("count").asLong()).as(filter + " " + direction + " count").isEqualTo(counted.size());
                assertThat(card.get("total").decimalValue()).as(filter + " " + direction + " total").isEqualByComparingTo(sum);
                assertThat(card.get("canceledCount").asLong()).as(filter + " " + direction + " canceled").isEqualTo(goods.size() - counted.size());
                checked++;
            }
            // the items line: with a CFOP filter, the sum of the list's items in those CFOPs (read from the DB, outside the API)
            if (filter.containsKey("cfops")) {
                assertThat(totals.body().get("cfopItems").isNull()).isFalse();
            } else {
                assertThat(totals.body().get("cfopItems").isNull()).as(filter + " cfopItems null").isTrue();
            }
        }
        System.out.println("QA-RANDOM-MATRIX cards conferidos: " + checked);
    }

    // ================================================================== 2. the spec's numbers over HTTP

    @Test
    void specNumbersCt01ToCt20OverHttp() throws Exception {
        JsonNode month = totals(a, Map.of(), admin);
        assertCard(month.get("inbound"), 4, "2850.00", "336.00", 1);
        assertThat(month.get("filtered").asBoolean()).isFalse();
        assertThat(month.get("cfopItems").isNull()).isTrue();
        assertThat(numbers(a, Map.of(), admin)).containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6", "7");

        JsonNode ct10 = totals(a, Map.of("cfops", List.of("5102")), admin);
        assertCard(ct10.get("inbound"), 3, "2550.00", "300.00", 1);
        assertThat(ct10.get("filtered").asBoolean()).isTrue();
        assertItems(ct10.get("cfopItems").get("inbound"), 3, "2100.00", "252.00");
        assertThat(numbers(a, Map.of("cfops", List.of("5102")), admin)).containsExactlyInAnyOrder("1", "2", "4", "5", "7");

        JsonNode ct11 = totals(a, Map.of("cfops", List.of("5.102", "6102", "5102")), admin);
        assertCard(ct11.get("inbound"), 4, "2850.00", "336.00", 1);
        assertItems(ct11.get("cfopItems").get("inbound"), 4, "2400.00", "288.00");
        assertThat(numbers(a, Map.of("cfops", List.of("5.102", "6102", "5102")), admin)).containsExactlyInAnyOrder("1", "2", "3", "4", "5", "7");

        JsonNode ct12 = totals(a, Map.of("cfops", List.of("5102"), "participant", "alfa"), admin);
        assertCard(ct12.get("inbound"), 1, "1000.00", "120.00", 0);
        assertItems(ct12.get("cfopItems").get("inbound"), 1, "600.00", "72.00");

        JsonNode ct13 = totals(a, Map.of("cfops", List.of("5405")), admin);
        assertCard(ct13.get("inbound"), 1, "1000.00", "120.00", 0);
        assertItems(ct13.get("cfopItems").get("inbound"), 1, "400.00", "48.00");

        JsonNode ct14 = totals(a, Map.of("reviewStatus", "REVIEWED"), admin);
        assertCard(ct14.get("inbound"), 1, "500.00", "60.00", 0);
        assertThat(ct14.get("filtered").asBoolean()).isTrue();
        assertThat(ct14.get("cfopItems").isNull()).isTrue();

        jdbc.update("UPDATE fiscal_documents SET review_status = 'DIVERGENT' WHERE id IN (?, ?)", n3, n5);
        JsonNode ct15 = totals(a, Map.of("divergencia", true), admin);
        assertCard(ct15.get("inbound"), 2, "1350.00", "156.00", 0);
        assertThat(numbers(a, Map.of("divergencia", true), admin)).containsExactlyInAnyOrder("3", "5");

        JsonNode ct16 = totals(a, Map.of("cfops", List.of("1102")), admin);
        assertCard(ct16.get("inbound"), 0, "0", "0", 0);
        assertCard(ct16.get("outbound"), 0, "0", "0", 0);
        assertThat(ct16.get("filtered").asBoolean()).isTrue();
        assertItems(ct16.get("cfopItems").get("inbound"), 0, "0", "0");
        assertItems(ct16.get("cfopItems").get("outbound"), 0, "0", "0");

        assertThat(totals(a, Map.of("direction", "INBOUND", "cfops", List.of("5102")), admin)).isEqualTo(ct10); // CT-17
        assertThat(totals(a, Map.of("cfop", "5102"), admin)).isEqualTo(ct10); // CT-18
        assertThat(numbers(a, Map.of("cfop", "51"), admin)).isEmpty(); // CT-19 / CA-28
        assertThat(totals(a, Map.of("cfop", "6102", "cfops", List.of("5102")), admin)).isEqualTo(ct10); // CT-20
        assertThat(totals(a, Map.of("cfops", List.of(), "cfop", "5102"), admin)).as("cfops [] cai no legado").isEqualTo(ct10);
    }

    /** CT-26 and the exact edges of the month, to the microsecond (IssuePeriod uses LocalTime.MAX = 23:59:59.999999999). */
    @Test
    void periodEdgesAreExact() throws Exception {
        String last = note(a, "INBOUND", "AUTHORIZED", null, "201", "Fornecedor Alfa", "SIEG", LocalDateTime.of(2026, 11, 30, 23, 59, 59, 999_999_000),
                "10.00", "1.00", "SP");
        item(last, 1, "9001", "9001", "10.00", "1.00");
        String first = note(a, "INBOUND", "AUTHORIZED", null, "202", "Fornecedor Alfa", "SIEG", LocalDateTime.of(2026, 11, 1, 0, 0, 0),
                "20.00", "2.00", "SP");
        item(first, 1, "9001", "9001", "20.00", "2.00");
        String before = note(a, "INBOUND", "AUTHORIZED", null, "204", "Fornecedor Alfa", "SIEG", LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_999_000),
                "80.00", "8.00", "SP");
        item(before, 1, "9001", "9001", "80.00", "8.00");

        assertThat(numbers(a, Map.of("cfops", List.of("9001")), admin)).containsExactlyInAnyOrder("201", "202");
        assertCard(totals(a, Map.of("cfops", List.of("9001")), admin).get("inbound"), 2, "30.00", "3.00", 0);
        Map<String, Object> octoberOnly = Map.of("cfops", List.of("9001"), "dataEmissaoInicio", "2026-10-01", "dataEmissaoFim", "2026-10-31");
        assertThat(numbers(a, octoberOnly, admin)).as("outubro tem a 204 (31/10 23:59:59.999999); 202 é de 01/11 00:00:00 (BUG de arredondamento: entra)")
                .contains("204");
    }

    /**
     * BUG (known, pre-existing in IssuePeriod.end): the period ends at LocalTime.MAX (23:59:59.999999999) and the MySQL driver
     * rounds the nanos up to 2026-12-01 00:00:00, so a note issued exactly at midnight of the next day is counted in November.
     */
    @Test
    void aNoteAtMidnightOfTheNextMonthIsNotInTheMonth() throws Exception {
        String next = note(a, "INBOUND", "AUTHORIZED", null, "203", "Fornecedor Alfa", "SIEG", LocalDateTime.of(2026, 12, 1, 0, 0, 0),
                "40.00", "4.00", "SP");
        item(next, 1, "9001", "9001", "40.00", "4.00");
        String firstOfNovember = note(a, "INBOUND", "AUTHORIZED", null, "205", "Fornecedor Alfa", "SIEG", LocalDateTime.of(2026, 11, 1, 0, 0, 0),
                "5.00", "0.50", "SP");
        item(firstOfNovember, 1, "9001", "9001", "5.00", "0.50");
        assertThat(numbers(a, Map.of("cfops", List.of("9001")), admin)).as("01/12 00:00:00 não é de novembro").containsExactly("205");
        Map<String, Object> octoberStrict = Map.of("cfops", List.of("9001"), "dataEmissaoInicio", "2026-10-01", "dataEmissaoFim", "2026-10-31");
        assertThat(numbers(a, octoberStrict, admin)).as("01/11 00:00:00 não é de outubro").doesNotContain("205");
        Map<String, Object> december = Map.of("cfops", List.of("9001"), "dataEmissaoInicio", "2026-12-01", "dataEmissaoFim", "2026-12-31");
        assertThat(numbers(a, december, admin)).containsExactly("203");
    }

    // ================================================================== 3. errors and bad input

    @Test
    void cfopErrorsHaveTheDocumentedBodyAndOrderOfChecks() throws Exception {
        for (String route : List.of(LIST, TOTALS)) {
            Resp invalid = post(route, admin, bodyOf(a, Map.of("cfops", List.of("1102", "11", "")), 10));
            assertThat(invalid.status()).isEqualTo(400);
            JsonNode error = invalid.body();
            assertThat(error.get("code").asText()).isEqualTo("error.cfopFilter.invalid");
            assertThat(error.get("status").asInt()).isEqualTo(400);
            assertThat(error.has("timestamp")).isTrue();
            assertThat(error.get("invalidValues")).hasSize(2);
            assertThat(error.get("invalidValues").get(0).asText()).isEqualTo("11");
            assertThat(error.get("invalidValues").get(1).asText()).isEmpty();
            assertThat(error.get("message").asText()).contains("CFOP");

            Resp many = post(route, admin, bodyOf(a, Map.of("cfops", IntStream.rangeClosed(1101, 1121).mapToObj(String::valueOf).toList()), 10));
            assertThat(many.status()).isEqualTo(400);
            assertThat(many.code()).isEqualTo("error.cfopFilter.tooMany");
            assertThat(many.body().get("maxItems").asInt()).isEqualTo(20);
            assertThat(many.body().get("message").asText()).contains("20");

            List<String> twentyOneWithRepeat = new ArrayList<>(IntStream.rangeClosed(1101, 1120).mapToObj(String::valueOf).toList());
            twentyOneWithRepeat.add("1.101");
            assertThat(post(route, admin, bodyOf(a, Map.of("cfops", twentyOneWithRepeat), 10)).status()).isEqualTo(200);

            // many invalid values: invalidValues is capped at 20
            List<String> manyInvalid = IntStream.range(0, 300).mapToObj(i -> "x" + i).toList();
            Resp capped = post(route, admin, bodyOf(a, Map.of("cfops", manyInvalid), 10));
            assertThat(capped.status()).isEqualTo(400);
            assertThat(capped.body().get("invalidValues")).hasSize(20);

            // another office's company is a 404 even with a broken filter: the access check goes first
            Resp outside = post(route, adminE2, bodyOf(a, Map.of("cfops", List.of("11")), 10));
            assertThat(outside.status()).as("404 antes do 400").isEqualTo(404);
            // the old field is never a 400
            assertThat(post(route, admin, bodyOf(a, Map.of("cfop", "12345678901"), 10)).status()).isEqualTo(200);
            assertThat(post(route, admin, bodyOf(a, Map.of("cfop", "'; DROP TABLE x;--"), 10)).status()).isEqualTo(200);
        }
    }

    @Test
    void hostileValuesNeverBreakTheQueryNorReturn5xx() throws Exception {
        String[] hostile = {"%", "_", "' OR '1'='1", "\r\n5102", "5102\r\nSet-Cookie: x=1", "1102; DROP TABLE fiscal_documents", "\u0000", "５１０２",
                "\u0665\u0661\u0660\u0662", "5102".repeat(5000), "<script>", "${jndi:ldap://x}", "' union select 1,2,3 -- "};
        for (String value : hostile) {
            for (String field : List.of("participant", "numero", "origem", "fonte", "cfop")) {
                for (String route : List.of(LIST, TOTALS)) {
                    Resp r = post(route, admin, bodyOf(a, Map.of(field, value), 10));
                    assertThat(r.status()).as(field + "=" + abbreviated(value) + " " + route).isIn(200, 400);
                    assertThat(r.raw).doesNotContain("SQLException").doesNotContain("org.hibernate");
                }
            }
            for (String route : List.of(LIST, TOTALS)) {
                Resp r = post(route, admin, bodyOf(a, Map.of("cfops", List.of(value)), 10));
                assertThat(r.status()).as("cfops=" + abbreviated(value)).isIn(200, 400);
                assertThat(r.raw).doesNotContain("SQLException").doesNotContain("org.hibernate");
            }
        }
        // participant with LIKE wildcards: "%" and "_" must not match everything (the pattern is "%...%" of the typed text)
        assertThat(numbers(a, Map.of("participant", "%"), admin)).as("participant=% é literal? (registro)").isNotNull();
        // the table is still there
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fiscal_documents WHERE company_id = ?", Integer.class, a)).isGreaterThan(5);
    }

    @Test
    void wrongJsonTypesAreA400Not5xx() throws Exception {
        String base = "\"companyId\":\"" + a + "\",\"dataEmissaoInicio\":\"2026-11-01\",\"dataEmissaoFim\":\"2026-11-30\"";
        String[] bodies = {
                "{" + base + ",\"cfops\":\"5102\"}", "{" + base + ",\"cfops\":5102}", "{" + base + ",\"cfops\":[5102]}",
                "{" + base + ",\"cfops\":[null]}", "{" + base + ",\"cfops\":null}", "{" + base + ",\"cfops\":[[\"5102\"]]}",
                "{" + base + ",\"cfops\":{\"a\":1}}", "{" + base + ",\"cfop\":5102}", "{" + base + ",\"cfop\":[\"5102\"]}",
                "{" + base + ",\"divergencia\":\"talvez\"}", "{" + base + ",\"direction\":\"SIDEWAYS\"}", "{" + base + ",\"reviewStatus\":\"X\"}"};
        for (String body : bodies) {
            for (String route : List.of(LIST, TOTALS)) {
                Resp r = post(route, admin, body);
                System.out.println("QA-TYPE " + route + " " + body.substring(base.length() + 1, body.length() - 1) + " -> " + r.status() + " " + r.code());
                assertThat(r.status()).as(body).isLessThan(500);
            }
        }
        // [5102] as a number: Jackson coerces it to "5102" or it is a 400 - either is fine, never 5xx
        // an XML/not-JSON body and an empty body
        assertThat(post(TOTALS, admin, "").status()).isLessThan(500);
        assertThat(post(TOTALS, admin, "<xml/>").status()).isLessThan(500);
    }

    // ================================================================== 4. access and isolation

    @Test
    void accessAndIsolationOverHttp() throws Exception {
        String ok = bodyOf(a, Map.of("cfops", List.of("5102")), 10);
        for (String route : List.of(LIST, TOTALS)) {
            assertThat(post(route, null, ok).status()).as("sem token " + route).isEqualTo(401);
            assertThat(post(route, "eyJhbGciOiJIUzI1NiJ9.e30.invalido", ok).status()).as("token inválido " + route).isEqualTo(401);
            // CT-23/24 and the companies outside the portfolio / the office
            for (String[] call : new String[][]{{fiscal1, a2}, {fiscal1, b}, {adminE2, a}, {adminZ, a}, {management, a2}}) {
                Resp plain = post(route, call[0], bodyOf(call[1], Map.of(), 10));
                Resp filtered = post(route, call[0], bodyOf(call[1], Map.of("cfops", List.of("5102")), 10));
                assertThat(filtered.status()).as(route + " " + call[1]).isEqualTo(404);
                assertThat(filtered.code()).isEqualTo(plain.code());
                assertThat(filtered.raw).doesNotContain("Fornecedor").doesNotContain(CNPJ_A);
            }
            // inexistent and malformed company ids
            assertThat(post(route, admin, bodyOf(UUID.randomUUID().toString(), Map.of("cfops", List.of("5102")), 10)).status()).isEqualTo(404);
            assertThat(post(route, admin, bodyOf("nao-e-uuid", Map.of("cfops", List.of("5102")), 10)).status()).isIn(400, 404);
            // allowed profiles
            assertThat(post(route, fiscal1, ok).status()).as("FISCAL na carteira").isEqualTo(200);
            assertThat(post(route, management, ok).status()).as("MANAGEMENT na carteira").isEqualTo(200);
            assertThat(post(route, financial, ok).status()).as("FINANCIAL sem INVOICES_VIEW").isEqualTo(403);
            // the platform console has no access to a client's notes (F1-RN-15)
            Resp platform = post(route, csadmin, ok);
            System.out.println("QA-PLATAFORMA " + route + " -> " + platform.status() + " " + platform.code());
            assertThat(platform.status()).as("plataforma não vê nota de cliente").isIn(403, 404);
        }
        // blocked office reads (CT-25, CA-32)
        assertCard(totals(z, Map.of("cfops", List.of("5102")), adminZ).get("inbound"), 1, "1000.00", "120.00", 0);
        assertThat(post(LIST, adminZ, bodyOf(z, Map.of("cfops", List.of("5102")), 10)).body().get("content")).hasSize(1);
        assertThat(get(FRONTEIRA + "?companyId=" + z + "&periodo=2026-11&cfops=5102", adminZ).status()).isEqualTo(200);
        // FISCAL sees the same totals as the admin on company A (the filter never changes the scope inside it)
        assertThat(totals(a, Map.of("cfops", List.of("5102")), fiscal1)).isEqualTo(totals(a, Map.of("cfops", List.of("5102")), admin));
        // the body of a 404 reveals nothing of the other company
        Resp outside = post(TOTALS, adminE2, bodyOf(a, Map.of("cfops", List.of("5102")), 10));
        assertThat(outside.raw).doesNotContain(a).doesNotContain("Comércio");
    }

    // ================================================================== 5. concurrency (CT-28)

    @Test
    void parallelRequestsWithDifferentFiltersGetTheirOwnTotals() throws Exception {
        List<Map<String, Object>> filters = List.of(Map.of("cfops", List.of("5102")), Map.of("cfops", List.of("5405")),
                Map.of("cfops", List.of("6102")), Map.of("reviewStatus", "REVIEWED"), Map.of("participant", "alfa"), Map.of());
        Map<Integer, JsonNode> expected = new LinkedHashMap<>();
        for (int i = 0; i < filters.size(); i++) {
            expected.put(i, totals(a, filters.get(i), admin));
        }
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int round = 0; round < 8; round++) {
                for (int i = 0; i < filters.size(); i++) {
                    int index = i;
                    results.add(pool.submit((Callable<Boolean>) () ->
                            expected.get(index).equals(totals(a, filters.get(index), admin))));
                }
            }
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ================================================================== 6. Fronteira over HTTP (CT-30 to CT-37)

    @Test
    void fronteiraCutCt30ToCt37() throws Exception {
        String base = FRONTEIRA + "?companyId=" + f + "&periodo=2026-11";
        JsonNode whole = get(base, admin).body();
        assertThat(whole.get("cfopFilter").isNull()).isTrue();
        assertThat(whole.get("lines")).hasSize(4);

        // CT-30
        Resp ct30 = get(base + "&cfops=6102", admin);
        assertThat(ct30.status()).isEqualTo(200);
        JsonNode cut = ct30.body().get("cfopFilter");
        assertThat(ints(cut.get("matchingLines"))).containsExactly(1, 4);
        assertThat(cut.get("linesWithoutXml").asInt()).isEqualTo(1);
        assertThat(cut.get("cfops").toString()).isEqualTo("[\"6102\"]");
        assertThat(cut.get("matchingUncharged")).hasSize(1);
        assertThat(cut.get("matchingUncharged").get(0).asText()).isEqualTo(uncharged1);
        JsonNode totals = cut.get("totals");
        assertThat(totals.get("notes").asInt()).isEqualTo(2);
        assertThat(totals.get("invoicesAmount").decimalValue()).isEqualByComparingTo("1900.00");
        assertThat(totals.get("icmsCharged").decimalValue()).isEqualByComparingTo("190.00");
        assertThat(totals.get("unchargedCount").asInt()).isEqualTo(1);
        assertThat(totals.get("icmsCalculated").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(totals.get("chargedAbove").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(totals.get("chargedBelow").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(totals.get("unchargedPreview").decimalValue()).isEqualByComparingTo("45.00");
        JsonNode summary = ct30.body().get("summary");
        assertThat(summary.get("cobradoAMais").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(summary.get("valorGuia").decimalValue()).isEqualByComparingTo("280.00");
        assertCutMatchesLines(ct30.body());
        // CT-24 / CF-RN-24: everything else is the whole extract, untouched
        for (String field : List.of("lines", "semCobranca", "summary", "previaIcms", "periods")) {
            assertThat(ct30.body().get(field)).as("campo " + field + " inalterado").isEqualTo(whole.get(field));
        }
        assertThat(whole.get("summary").get("icmsDevido").decimalValue()).isEqualByComparingTo("310.00");

        // CT-31
        JsonNode ct31 = get(base + "&cfops=6403", admin).body();
        assertThat(ints(ct31.get("cfopFilter").get("matchingLines"))).containsExactly(2, 4);
        assertThat(ct31.get("cfopFilter").get("totals").get("invoicesAmount").decimalValue()).isEqualByComparingTo("1700.00");
        assertThat(ct31.get("cfopFilter").get("totals").get("icmsCharged").decimalValue()).isEqualByComparingTo("170.00");
        assertThat(ct31.get("cfopFilter").get("matchingUncharged")).isEmpty();
        assertThat(ct31.get("cfopFilter").get("totals").get("icmsCalculated").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(ct31.get("cfopFilter").get("totals").get("chargedAbove").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(ct31.get("cfopFilter").get("totals").get("chargedBelow").decimalValue()).isEqualByComparingTo("10.00");
        assertCutMatchesLines(ct31);

        // CT-32, comma and repeated forms, and unsorted/duplicated input
        JsonNode comma = get(base + "&cfops=6403,6102", admin).body();
        JsonNode repeated = get(base + "&cfops=6102&cfops=6403", admin).body();
        JsonNode dirty = get(base + "&cfops=6.403&cfops=6102&cfops=6102", admin).body();
        assertThat(comma.get("cfopFilter")).isEqualTo(repeated.get("cfopFilter")).isEqualTo(dirty.get("cfopFilter"));
        assertThat(ints(comma.get("cfopFilter").get("matchingLines"))).containsExactly(1, 2, 4);
        assertThat(comma.get("cfopFilter").get("totals").get("notes").asInt()).isEqualTo(3);
        assertThat(comma.get("cfopFilter").get("totals").get("icmsCharged").decimalValue()).isEqualByComparingTo("270.00");
        assertThat(comma.get("cfopFilter").get("cfops").toString()).isEqualTo("[\"6102\",\"6403\"]");
        assertCutMatchesLines(comma);

        // mixing the two forms (a repeated parameter that itself has a comma) is not split: documented as 400
        Resp mixed = get(base + "&cfops=6102,6403&cfops=6949", admin);
        System.out.println("QA-MIXED-FORMS status=" + mixed.status() + " " + mixed.code());
        // an unknown CFOP: nothing matches, the lines without xml are still reported
        JsonNode none = get(base + "&cfops=1102", admin).body().get("cfopFilter");
        assertThat(none.get("matchingLines")).isEmpty();
        assertThat(none.get("totals").get("notes").asInt()).isZero();
        assertThat(none.get("linesWithoutXml").asInt()).isEqualTo(1);

        // CT-34: month without extract -> the prévia only
        JsonNode october = get(FRONTEIRA + "?companyId=" + f + "&periodo=2026-10&cfops=6102", admin).body();
        assertThat(october.get("lines")).isEmpty();
        assertThat(october.get("cfopFilter").get("matchingLines")).isEmpty();
        assertThat(october.get("cfopFilter").get("totals").get("notes").asInt()).isZero();
        assertThat(october.get("cfopFilter").get("linesWithoutXml").asInt()).isZero();
        assertThat(october.get("cfopFilter").get("matchingUncharged")).hasSize(1);
        assertThat(october.get("cfopFilter").get("totals").get("unchargedCount").asInt()).isEqualTo(1);

        // CT-36 and 400s
        Resp invalid = get(base + "&cfops=61", admin);
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("error.cfopFilter.invalid");
        assertThat(invalid.body().get("invalidValues").get(0).asText()).isEqualTo("61");
        assertThat(get(base + "&cfops=" + String.join(",", IntStream.rangeClosed(1101, 1121).mapToObj(String::valueOf).toList()), admin).code())
                .isEqualTo("error.cfopFilter.tooMany");
        assertThat(get(base + "&cfops=", admin).status()).as("cfops vazio = sem filtro").isEqualTo(200);
        assertThat(get(base + "&cfops=", admin).body().get("cfopFilter").isNull()).isTrue();
        assertThat(get(base + "&cfops=6102&cfops=", admin).status()).as("um item vazio no meio").isIn(200, 400);
        for (String hostile : List.of("%25", "'%20OR%201=1", "6102%0D%0Ax", "6102;", "%00")) {
            assertThat(get(base + "&cfops=" + hostile, admin).status()).as(hostile).isIn(200, 400);
        }

        // CT-37, access
        assertThat(get(base + "&cfops=6102", adminE2).status()).isEqualTo(404);
        assertThat(get(base + "&cfops=6102", null).status()).isEqualTo(401);
        assertThat(get(base + "&cfops=6102", fiscal1).status()).isEqualTo(200);
        assertThat(get(FRONTEIRA + "?companyId=" + a2 + "&periodo=2026-11&cfops=6102", fiscal1).status()).as("fora da carteira").isEqualTo(404);
        assertThat(get(base + "&cfops=6102", financial).status()).isEqualTo(403);
    }

    /** CT-35 and CF-RN-24: the reports ignore {@code cfops}. */
    @Test
    void fronteiraReportsAreOfTheWholeMonth() throws Exception {
        String base = FRONTEIRA + "/relatorio.csv?companyId=" + f + "&periodo=2026-11";
        Resp plain = get(base, admin);
        Resp withCfops = get(base + "&cfops=6102", admin);
        System.out.println("QA-CSV status=" + plain.status() + "/" + withCfops.status() + " bytes=" + plain.bytes().length + "/" + withCfops.bytes().length);
        assertThat(plain.status()).isEqualTo(200);
        assertThat(withCfops.status()).isEqualTo(200);
        assertThat(withCfops.bytes()).isEqualTo(plain.bytes());
        // 4 lines of extract in the CSV
        long dataLines = new String(plain.bytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).count();
        assertThat(dataLines).isGreaterThanOrEqualTo(5);
        for (String path : List.of("/relatorio.pdf", "/pedido-revisao.pdf", "/pedido-revisao.txt")) {
            String url = FRONTEIRA + path + "?companyId=" + f + "&periodo=2026-11";
            Resp one = get(url, admin);
            Resp two = get(url + "&cfops=6102", admin);
            assertThat(one.status()).as(path).isEqualTo(two.status());
            if (path.endsWith(".txt")) {
                assertThat(two.raw()).isEqualTo(one.raw());
            }
        }
    }

    // ================================================================== helpers

    private void seedFronteira() {
        String extract = UUID.randomUUID().toString();
        Timestamp at = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO efisco_extracts (id, company_id, periodo, numero_extrato, imported_at, imported_by, deleted, created_by,"
                + " created_date) VALUES (?, ?, '2026-11', '0001', ?, 'qa', 0, 'qa', ?)", extract, f, at, at);
        String x = ReformItSupport.cnpj("91111111", "0001");
        String y = ReformItSupport.cnpj("92222222", "0001");
        String zz = ReformItSupport.cnpj("93333333", "0001");
        String w = ReformItSupport.cnpj("94444444", "0001");
        LocalDateTime nov = LocalDateTime.of(2026, 11, 12, 10, 0);
        String dx = interstate(f, x, "701", "1000.00", nov);
        item(dx, 1, "6102", "6102", "1000.00", "120.00");
        String dy = interstate(f, y, "702", "800.00", nov);
        item(dy, 1, "6403", "6403", "800.00", "96.00");
        String dz = interstate(f, zz, "704", "900.00", nov);
        item(dz, 1, "6102", "6102", "450.00", "54.00");
        item(dz, 2, "6403", "6403", "450.00", "54.00");
        extractNote(extract, 1, "701", x, "1000.00", "100.00");
        extractNote(extract, 2, "702", y, "800.00", "80.00");
        extractNote(extract, 3, "703", w, "400.00", "40.00"); // no XML
        extractNote(extract, 4, "704", zz, "900.00", "90.00");
        // entries without charge (not in the extract): U1 (6102) and U2 (6949), also one in October
        uncharged1 = interstate(f, ReformItSupport.cnpj("95555555", "0001"), "801", "450.00", nov);
        item(uncharged1, 1, "6102", "6102", "450.00", "54.00");
        uncharged2 = interstate(f, ReformItSupport.cnpj("96666666", "0001"), "802", "120.00", nov);
        item(uncharged2, 1, "6949", "6949", "120.00", "14.40");
        // the spec's calculation (min = max): modal internal rate + one formula per supplier, so L1 = 100, L2 = 50, L4 = 100, U1 = 45, U2 = 12
        rule("{\"companyId\":\"" + f + "\",\"kind\":\"ALIQUOTA_INTERNA\",\"descricao\":\"modal QA\",\"aliquota\":20.5,\"fundamento\":\"teste qa\"}");
        formula(x, "valor_produto * 0.1");
        formula(y, "valor_produto * 0.0625");
        formula(zz, "valor_produto / 9");
        formula(ReformItSupport.cnpj("95555555", "0001"), "valor_produto * 0.1");
        formula(ReformItSupport.cnpj("96666666", "0001"), "valor_produto * 0.1");
        String oct = interstate(f, ReformItSupport.cnpj("97777777", "0001"), "803", "300.00", LocalDateTime.of(2026, 10, 12, 10, 0));
        item(oct, 1, "6102", "6102", "300.00", "36.00");
    }

    private void formula(String supplier, String formula) {
        rule("{\"companyId\":\"" + f + "\",\"kind\":\"FORMULA\",\"descricao\":\"qa\",\"cnpjFornecedor\":\"" + supplier
                + "\",\"formula\":\"" + formula + "\",\"fundamento\":\"teste qa\"}");
    }

    private void rule(String body) {
        try {
            Resp r = post("/fronteira/regras", admin, body);
            assertThat(r.status()).as(r.raw).isEqualTo(200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String interstate(String company, String issuerCnpj, String number, String total, LocalDateTime when) {
        String id = note(company, "INBOUND", "AUTHORIZED", null, number, "Fornecedor Fronteira " + number, "SIEG", when, total, "0.00", "SP");
        jdbc.update("UPDATE fiscal_documents SET issuer_document = ? WHERE id = ?", issuerCnpj, id);
        return id;
    }

    private void extractNote(String extract, int line, String number, String cnpj, String total, String icms) {
        Timestamp at = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO efisco_extract_notes (id, extract_id, linha, registro, data_passagem, numero_nota, uf, valor_nota, icms_devido,"
                        + " cnpj_emitente, deleted, created_by, created_date) VALUES (?, ?, ?, ?, '12/11/2026', ?, 'SP', ?, ?, ?, 0, 'qa', ?)",
                UUID.randomUUID().toString(), extract, line, "R" + line, number, new BigDecimal(total), new BigDecimal(icms), cnpj, at);
    }

    /** The cut's totals must be what the whole report's lines say about the matching ones (the same accounts as the summary). */
    private void assertCutMatchesLines(JsonNode report) {
        JsonNode cut = report.get("cfopFilter");
        TreeSet<Integer> matching = new TreeSet<>(ints(cut.get("matchingLines")));
        BigDecimal invoices = BigDecimal.ZERO;
        BigDecimal charged = BigDecimal.ZERO;
        BigDecimal calculated = BigDecimal.ZERO;
        BigDecimal above = BigDecimal.ZERO;
        BigDecimal below = BigDecimal.ZERO;
        int notes = 0;
        for (JsonNode line : report.get("lines")) {
            if (!matching.contains(line.get("linha").asInt())) {
                continue;
            }
            notes++;
            invoices = invoices.add(line.get("valorNota").decimalValue());
            charged = charged.add(line.get("icmsDevido").decimalValue());
            JsonNode calc = line.get("calculo");
            if (calc != null && !calc.isNull() && !calc.get("semRegra").asBoolean()) {
                calculated = calculated.add(calc.get("valor").decimalValue());
            }
            String result = line.get("resultado").asText();
            JsonNode difference = line.get("diferenca");
            if ("COBROU_A_MAIS".equals(result) && difference != null && !difference.isNull()) {
                above = above.add(difference.decimalValue());
            } else if (("COBROU_A_MENOS".equals(result) || "NAO_CALCULOU".equals(result)) && difference != null && !difference.isNull()) {
                below = below.add(difference.decimalValue().abs());
            }
        }
        JsonNode totals = cut.get("totals");
        assertThat(totals.get("notes").asInt()).as("notes").isEqualTo(notes);
        assertThat(totals.get("invoicesAmount").decimalValue()).as("invoicesAmount").isEqualByComparingTo(invoices);
        assertThat(totals.get("icmsCharged").decimalValue()).as("icmsCharged").isEqualByComparingTo(charged);
        assertThat(totals.get("icmsCalculated").decimalValue()).as("icmsCalculated").isEqualByComparingTo(calculated);
        assertThat(totals.get("chargedAbove").decimalValue()).as("chargedAbove").isEqualByComparingTo(above);
        System.out.println("QA-FRONTEIRA-CUT lines=" + matching + " notes=" + notes + " charged=" + charged + " calc=" + calculated
                + " above=" + above + " below(client)=" + below + " below(api)=" + totals.get("chargedBelow").decimalValue()
                + " resultados=" + resultados(report));
        // chargedBelow: the same sign convention is verified separately below when the lines carry a difference
        assertThat(totals.get("chargedBelow").decimalValue().signum()).isGreaterThanOrEqualTo(0);
    }

    private static String resultados(JsonNode report) {
        List<String> out = new ArrayList<>();
        for (JsonNode line : report.get("lines")) {
            out.add(line.get("linha").asInt() + ":" + line.get("resultado").asText() + ":" + line.get("diferenca"));
        }
        return out.toString();
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asInt()));
        return values;
    }

    private static String abbreviated(String value) {
        return value.length() > 30 ? value.substring(0, 30) + "..(" + value.length() + ")" : value.replace("\r", "\\r").replace("\n", "\\n");
    }

    private String token(String login) throws Exception {
        String auth = Base64.getEncoder().encodeToString((it.login(login) + ":" + ReformItSupport.PASSWORD).getBytes(StandardCharsets.UTF_8));
        Resp r = send("POST", "/auth", null, "{\"auth\":\"" + auth + "\"}");
        assertThat(r.status()).as("login " + login).isEqualTo(200);
        String token = r.body().get("token").asText();
        secrets.add(token);
        return token;
    }

    private Resp post(String path, String token, String body) throws Exception {
        return send("POST", path, token, body);
    }

    private Resp get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private Resp send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/taxify-service/api/v1" + path))
                .header("Content-Type", "application/json").header("Accept-Language", "pt-BR");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        String raw = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() >= 500) {
            System.out.println("QA-5XX " + method + " " + path + " -> " + response.statusCode() + " " + raw);
        }
        return new Resp(response.statusCode(), raw, response.body(), json);
    }

    private String bodyOf(String company, Map<String, Object> filter, int size) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("companyId", company);
        fields.put("dataEmissaoInicio", "2026-11-01");
        fields.put("dataEmissaoFim", "2026-11-30");
        fields.putAll(filter);
        fields.put("pagination", Map.of("page", 0, "size", size));
        return json.writeValueAsString(fields);
    }

    private JsonNode totals(String company, Map<String, Object> filter, String token) throws Exception {
        Resp r = post(TOTALS, token, bodyOf(company, filter, 10));
        assertThat(r.status()).as(r.raw).isEqualTo(200);
        return r.body();
    }

    private List<String> numbers(String company, Map<String, Object> filter, String token) throws Exception {
        Resp r = post(LIST, token, bodyOf(company, filter, 200));
        assertThat(r.status()).as(r.raw).isEqualTo(200);
        List<String> numbers = new ArrayList<>();
        r.body().get("content").forEach(row -> numbers.add(row.get("numero").asText()));
        return numbers;
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
                        String source, LocalDateTime issuedAt, String total, String icms, String issuerUf) {
        String id = UUID.randomUUID().toString();
        boolean outbound = "OUTBOUND".equals(direction);
        jdbc.update("INSERT INTO fiscal_documents (id, deleted, created_date, created_by, document_type, model, source_system, company_id,"
                        + " xml_file_path, direction, company_role, status, issue_date, metadata_version, number, issuer_name,"
                        + " issuer_document, issuer_document_type, issuer_uf, recipient_name, recipient_document, recipient_document_type,"
                        + " review_status, total_amount, icms_amount, icms_st_amount, ipi_amount, pis_amount, cofins_amount)"
                        + " VALUES (?, 0, ?, 'it', 'NFE', '55', ?, ?, 'xml/ficticio.xml', ?, ?, ?, ?, 0, ?, ?, ?, 'CNPJ', ?,"
                        + " 'Comércio Exemplo Ltda', '11222333000181', 'CNPJ', ?, ?, ?, 0, 0, 0, 0)",
                id, Timestamp.valueOf(NOW), source, company, direction, outbound ? "ISSUER" : "RECIPIENT", status,
                Timestamp.valueOf(issuedAt), number, issuer, outbound ? "11222333000181" : "12345678000195", issuerUf, review,
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
                UUID.randomUUID().toString(), document, number, "P-" + number, cfop, effectiveCfop, new BigDecimal(amount), new BigDecimal(icms));
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

    @SuppressWarnings("unused")
    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
