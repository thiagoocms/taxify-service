package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.config.StockSurveyProperties;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.reform.StockSurveyScheduler;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static br.com.cshub.taxify.rest.ReformItSupport.BASE;
import static br.com.cshub.taxify.rest.ReformItSupport.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Stock credit survey (spec 2026-11-27, F5a) end to end on the local MySQL, with the spec's fictitious scenario
 * (section 12): firm A with E1 (Presumido), E2 (no regime), E3 (Simples, then Presumido from 2026-07) and E4 (Real,
 * Simples from 2027-01); the ADMIN, a FISCAL with E1 and E2 and an ACCOUNTING user; firm B blocked; firm C for the
 * isolation. Today is 2026-12-10 06:15 in Recife. The routine runs over every company of the database (all fictitious
 * in this local one); its rows and the day's routine_runs row are removed after each test.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.reform.stock-survey.enabled=true", "taxify.reform.stock-survey.cron=-",
        "taxify.reform.stock-survey.retry-cron=-",
        "taxify.capture-priority.purge-enabled=false",
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false"
})
@AutoConfigureMockMvc
class StockCreditSurveyIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 10, 6, 15);
    private static final String SURVEY = BASE + "/reform/stock-credit";

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock stockSurveyTestClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MutableClock clock;
    @Autowired
    private StockSurveyScheduler scheduler;
    @Autowired
    private StockSurveyProperties properties;

    private ReformItSupport it;
    private String e1;
    private String e2;
    private String e3;
    private String e4;
    private String w;
    private String admin;
    private String fiscal1;
    private String contabil;
    private String adminB;
    private String adminC;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void setUp() throws Exception {
        it = new ReformItSupport(mvc, json, jdbc, passwordEncoder, NOW, "itce");
        it.assumeLocalDatabase();
        clock.set(NOW);
        properties.setEnabled(true);
        properties.setRefreshCooldownMinutes(10);
        properties.setStaleMinutes(120);
        cleanSurvey();

        String firmA = it.firm("Escritório Exemplo", "ACTIVE");
        e1 = it.company(firmA, "Atacado Exemplo Ltda", "11222333000181", null);
        e2 = it.company(firmA, "Bebidas Modelo ME", "12345678000195", null);
        e3 = it.company(firmA, "=SOMA(A1) Comércio Ltda", "33444555000181", "SIMPLES_NACIONAL");
        e4 = it.company(firmA, "Indústria Teste SA", "44555666000181", "LUCRO_REAL");
        regime(e1, "LUCRO_PRESUMIDO", LocalDate.of(2025, 1, 1));
        regime(e3, "SIMPLES_NACIONAL", null);
        regime(e3, "LUCRO_PRESUMIDO", LocalDate.of(2026, 7, 1));
        regime(e4, "LUCRO_REAL", LocalDate.of(2025, 1, 1));
        regime(e4, "SIMPLES_NACIONAL", LocalDate.of(2027, 1, 1));
        notesOfE1();
        // E2: a 5102 item with no value (CT-CE-32)
        item(note(e2, "INBOUND", null, LocalDateTime.of(2026, 4, 2, 9, 0), false), "5102", null, "01", "22030000");

        it.user(firmA, "admin", "ADMIN", null);
        String fiscal = it.user(firmA, "fiscal1", "EMPLOYEE", "FISCAL");
        it.portfolio(fiscal, e1);
        it.portfolio(fiscal, e2);
        String accounting = it.user(firmA, "contabil", "EMPLOYEE", "ACCOUNTING");
        it.portfolio(accounting, e1);

        String firmB = it.firm("Escritório Bloqueado", "BLOCKED");
        w = it.company(firmB, "Confecção Fictícia SA", ReformItSupport.randomCnpj(), "LUCRO_PRESUMIDO");
        it.user(firmB, "admin.b", "ADMIN", null);
        String firmC = it.firm("Escritório Gama", "ACTIVE");
        it.company(firmC, "Outra Empresa Ltda", ReformItSupport.randomCnpj(), "LUCRO_REAL");
        it.user(firmC, "admin.c", "ADMIN", null);

        admin = it.token("admin");
        fiscal1 = it.token("fiscal1");
        contabil = it.token("contabil");
        adminB = it.token("admin.b");
        adminC = it.token("admin.c");
        logs.list.clear();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        properties.setEnabled(true);
        cleanSurvey();
        it.removeAll();
    }

    private void cleanSurvey() {
        jdbc.update("DELETE FROM stock_credit_survey_events");
        jdbc.update("DELETE FROM stock_credit_surveys");
        jdbc.update("DELETE FROM routine_runs WHERE routine = 'STOCK_SURVEY'");
    }

    // ================================================================== CE-RN-02 to CE-RN-08, CE-RN-11

    /** CA-CE-01 to CA-CE-04, CA-CE-08, CT-CE-01 to CT-CE-08, CT-CE-14, CT-CE-28, CT-CE-29. */
    @Test
    void routineClassifiesAndSums() throws Exception {
        assertThat(scheduler.runOnce()).isPresent();
        assertThat(scheduler.runOnce()).as("the second instance of the day").isEmpty();
        assertThat(jdbc.queryForList("SELECT run_date FROM routine_runs WHERE routine = 'STOCK_SURVEY'", java.sql.Date.class))
                .extracting(java.sql.Date::toLocalDate).containsExactly(LocalDate.of(2026, 12, 10));
        assertThat(messages()).contains("stock.survey.run.skipped runDate=2026-12-10 reason=ALREADY_RESERVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_credit_surveys WHERE company_id = ?", Integer.class, w))
                .as("blocked firm skipped").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_credit_surveys WHERE company_id = ?", Integer.class, e1)).isEqualTo(1);

        MvcResult listResult = it.call(get(SURVEY + "?year=2026"), admin);
        assertThat(status(listResult)).isEqualTo(200);
        String raw = listResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw).contains("\"yearPurchases\":168000.00");
        JsonNode list = it.body(listResult);
        assertThat(ids(list)).containsExactly(e1, e3, e2, e4);
        assertThat(list.get("notice").asText()).startsWith("Levantamento de quem pode ter direito ao crédito presumido de CBS");
        assertThat(list.at("/summary/companies").asInt()).isEqualTo(4);
        assertThat(list.at("/summary/notCalculated").asInt()).isZero();
        assertThat(list.at("/summary/byClassification/HYPOTHESIS_I").asInt()).isEqualTo(2);
        assertThat(list.at("/summary/byClassification/OUT_OF_SCOPE").asInt()).isEqualTo(1);
        assertThat(list.at("/summary/byStatus/NOT_EVALUATED").asInt()).isEqualTo(4);
        assertThat(list.at("/summary/oldestCalculatedAt").asText()).isEqualTo("2026-12-10T06:15:00-03:00");

        JsonNode rowE1 = row(list, e1);
        assertThat(rowE1.get("classification").asText()).isEqualTo("HYPOTHESIS_I");
        assertThat(rowE1.get("classificationReason").asText()).isEqualTo("Lucro Presumido em dez/2026: hipótese I (LC 214/2025, art. 381, I).");
        assertThat(rowE1.get("nextYearRegime").isNull()).isTrue();
        assertThat(rowE1.get("purchaseItems").asInt()).isEqualTo(4);
        assertThat(rowE1.get("inboundInvoices").asInt()).isEqualTo(4);
        assertThat(rowE1.get("cnpj").asText()).isEqualTo("11222333000181");
        assertThat(rowE1.get("status").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(rowE1.get("calculatedAt").asText()).isEqualTo("2026-12-10T06:15:00-03:00");
        JsonNode rowE4 = row(list, e4);
        assertThat(rowE4.get("classification").asText()).isEqualTo("OUT_OF_SCOPE");
        assertThat(rowE4.get("nextYearRegime").asText()).isEqualTo("SIMPLES_NACIONAL");
        assertThat(rowE4.get("classificationReason").asText()).startsWith("Cadastrada no Simples Nacional/MEI a partir de jan/2027");
        assertThat(row(list, e2).get("classification").asText()).isEqualTo("NO_REGIME");

        JsonNode detailE1 = it.ok(get(SURVEY + "/" + e1 + "?year=2026"), admin);
        assertThat(detailE1.get("referenceRegimeSource").asText()).isEqualTo("HISTORY");
        assertThat(detailE1.get("referenceRegimeSince").asText()).isEqualTo("2025-01-01");
        assertThat(detailE1.get("purchasesByMonth")).hasSize(12);
        assertThat(detailE1.at("/purchasesByMonth/2/amount").decimalValue()).isEqualByComparingTo("120000.00");
        assertThat(detailE1.at("/purchasesByMonth/11/amount").decimalValue()).as("31/12 23:30 is December").isEqualByComparingTo("8000.00");
        assertThat(detailE1.at("/purchasesByMonth/11/invoices").asInt()).isEqualTo(1);
        assertThat(detailE1.at("/purchasesByPisCst/0/cst").asText()).isEqualTo("01");
        assertThat(detailE1.at("/purchasesByPisCst/0/amount").decimalValue()).isEqualByComparingTo("120000.00");
        assertThat(detailE1.at("/purchasesByPisCst/1/cst").asText()).isEqualTo("04");
        assertThat(detailE1.at("/topNcm/0/ncm").asText()).isEqualTo("22030000");
        assertThat(detailE1.at("/topNcm/0/amount").decimalValue()).isEqualByComparingTo("130000.00");
        assertThat(detailE1.at("/topNcm/0/items").asInt()).isEqualTo(2);
        assertThat(detailE1.get("monthsWithInbound").asInt()).as("Mar, May, Jun (canceled out), Jul (5949), Dec").isEqualTo(4);
        assertThat(detailE1.get("monthsEvaluated").asInt()).isEqualTo(11);
        assertThat(detailE1.get("coverage").asText()).isEqualTo("PARTIAL");
        assertThat(detailE1.get("memory")).hasSize(6);
        assertThat(detailE1.at("/memory/4").asText()).contains("1101-1102, 1126");
        assertThat(detailE1.get("closed").asBoolean()).isFalse();

        JsonNode detailE3 = it.ok(get(SURVEY + "/" + e3 + "?year=2026"), admin);
        assertThat(detailE3.get("referenceRegime").asText()).isEqualTo("LUCRO_PRESUMIDO");
        assertThat(detailE3.get("referenceRegimeSince").asText()).isEqualTo("2026-07-01");
        assertThat(detailE3.get("nextYearRegime").isNull()).isTrue();
        assertThat(detailE3.get("coverage").asText()).isEqualTo("NO_INBOUND");
        assertThat(detailE3.get("yearPurchases").decimalValue()).isEqualByComparingTo("0.00");

        JsonNode detailE2 = it.ok(get(SURVEY + "/" + e2 + "?year=2026"), admin);
        assertThat(detailE2.get("referenceRegimeSource").asText()).isEqualTo("REGISTRATION");
        assertThat(detailE2.get("purchaseItems").asInt()).isEqualTo(1);
        assertThat(detailE2.get("yearPurchases").decimalValue()).isEqualByComparingTo("0.00");

        // filters and sort
        assertThat(ids(it.ok(get(SURVEY + "?year=2026&classification=HYPOTHESIS_I"), admin))).containsExactly(e1, e3);
        assertThat(ids(it.ok(get(SURVEY + "?year=2026&sort=companyName,asc"), admin))).containsExactly(e3, e1, e2, e4);
        assertThat(ids(it.ok(get(SURVEY + "?year=2026&search=11.222.333"), admin))).containsExactly(e1);
        assertThat(ids(it.ok(get(SURVEY + "?year=2026&search=bebidas"), admin))).containsExactly(e2);
        assertThat(ids(it.ok(get(SURVEY + "?year=2026&coverage=NO_INBOUND"), admin))).containsExactly(e3, e4);
        assertThat(ids(it.ok(get(SURVEY + "?year=2026&size=1&page=1"), admin))).containsExactly(e3);
        expectError(get(SURVEY + "?year=2026&classification=HIPOTESE_I"), admin, 400, "error.reform.stock.filter.invalid", "classification");
        expectError(get(SURVEY + "?year=2026&size=201"), admin, 400, "error.reform.stock.filter.invalid", "size");
        expectError(get(SURVEY + "?year=2026&sort=whatever"), admin, 400, "error.reform.stock.filter.invalid", "sort");
        expectError(get(SURVEY + "?year=2026&search=ab"), admin, 400, "error.reform.stock.filter.invalid", "search");
        expectError(get(SURVEY + "?year=2025"), admin, 400, "error.reform.stock.year.invalid", null);
        expectError(get(SURVEY + "/" + e1 + "?year=2025"), admin, 400, "error.reform.stock.year.invalid", null);
        assertThat(messages()).noneMatch(m -> m.contains("11222333000181") || m.contains("Atacado Exemplo"));
    }

    /** CT-CE-15: a run of the day started at 06:15 and never finished; another instance at 08:30 takes it over. */
    @Test
    void staleRunIsTakenOver() {
        jdbc.update("INSERT INTO routine_runs (id, routine, run_date, instance_id, started_at, deleted, created_by, created_date)"
                        + " VALUES (?, 'STOCK_SURVEY', '2026-12-10', 'other', ?, 0, 'it', ?)", UUID.randomUUID().toString(),
                Timestamp.valueOf(NOW), Timestamp.valueOf(NOW));
        clock.set(LocalDateTime.of(2026, 12, 10, 8, 0));
        assertThat(scheduler.runOnce()).as("105 min: not stale yet").isEmpty();
        clock.set(LocalDateTime.of(2026, 12, 10, 8, 30));
        assertThat(scheduler.runOnce()).isPresent();
        assertThat(jdbc.queryForObject("SELECT finished_at IS NOT NULL FROM routine_runs WHERE routine = 'STOCK_SURVEY'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM routine_runs WHERE routine = 'STOCK_SURVEY'", Integer.class)).isEqualTo(1);
    }

    // ================================================================== CE-RN-01: isolation and permissions

    /** CA-CE-05, CA-CE-15, CT-CE-22, CT-CE-23, CT-CE-30. */
    @Test
    void isolationAndPermissions() throws Exception {
        scheduler.runOnce();
        JsonNode fiscalList = it.ok(get(SURVEY + "?year=2026"), fiscal1);
        assertThat(ids(fiscalList)).containsExactly(e1, e2);
        assertThat(fiscalList.at("/summary/companies").asInt()).isEqualTo(2);
        expectError(get(SURVEY + "/" + e3 + "?year=2026"), fiscal1, 404, "error.Company.not.found", null);
        String csv = new String(it.call(get(SURVEY + "/export.csv?year=2026"), fiscal1).getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        assertThat(csv).contains("Atacado Exemplo Ltda").doesNotContain("SOMA(A1)").doesNotContain("Indústria Teste");

        expectError(get(SURVEY + "/" + e1 + "?year=2026"), adminC, 404, "error.Company.not.found", null);
        expectError(statusChange(e1, "NOTIFIED", null, 0), adminC, 404, "error.Company.not.found", null);
        assertThat(ids(it.ok(get(SURVEY + "?year=2026"), adminC))).doesNotContain(e1, e2, e3, e4);

        assertThat(status(it.call(get(SURVEY + "?year=2026"), contabil))).isEqualTo(200);
        assertThat(status(it.call(statusChange(e1, "NOTIFIED", null, 0), contabil))).isEqualTo(403);
        assertThat(status(it.call(post(SURVEY + "/" + e1 + "/refresh?year=2026"), contabil))).isEqualTo(403);
    }

    /** CA-CE-06 and CT-CE-24: the blocked firm reads (last calculation) and can't write. */
    @Test
    void blockedFirmReadsOnly() throws Exception {
        jdbc.update("INSERT INTO stock_credit_surveys (id, company_id, survey_year, classification, reference_regime, purchases_total,"
                        + " purchase_items, inbound_notes, months_with_inbound, months_evaluated, coverage, calculated_at, contact_status,"
                        + " version, deleted, created_by, created_date) VALUES (?, ?, 2026, 'HYPOTHESIS_I', 'LUCRO_PRESUMIDO', 10.00, 1, 1,"
                        + " 1, 10, 'PARTIAL', ?, 'NOT_EVALUATED', 0, 0, 'it', ?)", UUID.randomUUID().toString(), w,
                Timestamp.valueOf(NOW.minusDays(9)), Timestamp.valueOf(NOW.minusDays(9)));
        scheduler.runOnce();
        JsonNode list = it.ok(get(SURVEY + "?year=2026"), adminB);
        assertThat(ids(list)).containsExactly(w);
        assertThat(row(list, w).get("calculatedAt").asText()).as("not recalculated").isEqualTo("2026-12-01T06:15:00-03:00");
        assertThat(status(it.call(get(SURVEY + "/export.csv?year=2026"), adminB))).isEqualTo(200);
        expectError(post(SURVEY + "/" + w + "/refresh?year=2026"), adminB, 403, "error.firm.access.blocked", null);
        expectError(statusChange(w, "NOTIFIED", null, 0), adminB, 403, "error.firm.access.blocked", null);
    }

    // ================================================================== CE-RN-09, CE-RN-10

    /** CA-CE-07, CA-CE-09, CT-CE-16, CT-CE-19 to CT-CE-21. */
    @Test
    void contactStatus() throws Exception {
        scheduler.runOnce();
        clock.set(LocalDateTime.of(2026, 12, 11, 9, 30));
        JsonNode changed = it.ok(statusChange(e1, "INVENTORY_RECEIVED", "  Inventário fictício recebido  ", 0), fiscal1);
        assertThat(changed.get("status").asText()).isEqualTo("INVENTORY_RECEIVED");
        assertThat(changed.get("version").asInt()).isEqualTo(1);
        assertThat(changed.get("note").asText()).isEqualTo("Inventário fictício recebido");
        assertThat(changed.get("statusChangedBy").asText()).isEqualTo(it.login("fiscal1"));
        assertThat(changed.get("statusChangedAt").asText()).isEqualTo("2026-12-11T09:30:00-03:00");

        MvcResult stale = it.call(statusChange(e1, "DISCARDED", null, 0), admin);
        assertThat(status(stale)).isEqualTo(409);
        assertThat(it.code(stale)).isEqualTo("error.reform.stock.status.conflict");
        assertThat(it.body(stale).get("currentVersion").asInt()).isEqualTo(1);
        expectError(statusChange(e1, "AVISADA", null, 1), admin, 400, "error.reform.stock.status.invalid", null);
        expectError(statusChange(e1, "NOTIFIED", "x".repeat(501), 1), admin, 400, "error.reform.stock.note.too.long", null);

        JsonNode history = it.ok(get(SURVEY + "/" + e1 + "/history?year=2026"), admin);
        assertThat(history).hasSize(1);
        assertThat(history.at("/0/from").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(history.at("/0/to").asText()).isEqualTo("INVENTORY_RECEIVED");
        assertThat(history.at("/0/changedAt").asText()).isEqualTo("2026-12-11T09:30:00-03:00");
        assertThat(messages()).anyMatch(m -> m.startsWith("stock.survey.status.changed companyId=" + e1));
        assertThat(messages()).noneMatch(m -> m.contains("Inventário fictício"));

        // the next day's routine recalculates without touching the status (CE-RN-08 b)
        clock.set(LocalDateTime.of(2026, 12, 12, 6, 15));
        scheduler.runOnce();
        JsonNode after = it.ok(get(SURVEY + "/" + e1 + "?year=2026"), admin);
        assertThat(after.get("status").asText()).isEqualTo("INVENTORY_RECEIVED");
        assertThat(after.get("version").asInt()).isEqualTo(1);
        assertThat(after.get("note").asText()).isEqualTo("Inventário fictício recebido");
        assertThat(after.get("calculatedAt").asText()).isEqualTo("2026-12-12T06:15:00-03:00");

        // a company never calculated
        jdbc.update("DELETE FROM stock_credit_survey_events");
        jdbc.update("DELETE FROM stock_credit_surveys WHERE company_id = ?", e2);
        expectError(get(SURVEY + "/" + e2 + "?year=2026"), admin, 404, "error.reform.stock.survey.not.found", null);
        expectError(statusChange(e2, "NOTIFIED", null, 0), admin, 404, "error.reform.stock.survey.not.found", null);
        assertThat(it.ok(get(SURVEY + "?year=2026"), admin).at("/summary/notCalculated").asInt()).isEqualTo(1);
    }

    /** CA-CE-10, CT-CE-17, CT-CE-18: one refresh per 10 minutes, atomic. */
    @Test
    void refreshCooldown() throws Exception {
        clock.set(LocalDateTime.of(2026, 12, 10, 10, 0));
        JsonNode refreshed = it.ok(post(SURVEY + "/" + e1 + "/refresh?year=2026"), admin);
        assertThat(refreshed.get("yearPurchases").decimalValue()).isEqualByComparingTo("168000.00");
        assertThat(refreshed.get("refreshAvailableAt").asText()).isEqualTo("2026-12-10T10:10:00-03:00");

        clock.set(LocalDateTime.of(2026, 12, 10, 10, 4));
        MvcResult again = it.call(post(SURVEY + "/" + e1 + "/refresh?year=2026"), admin);
        assertThat(status(again)).isEqualTo(409);
        assertThat(it.code(again)).isEqualTo("error.reform.stock.refresh.cooldown");
        assertThat(it.body(again).get("retryAt").asText()).isEqualTo("2026-12-10T10:10:00-03:00");
        clock.set(LocalDateTime.of(2026, 12, 10, 10, 10));
        assertThat(status(it.call(post(SURVEY + "/" + e1 + "/refresh?year=2026"), admin))).isEqualTo(200);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return status(it.call(post(SURVEY + "/" + e2 + "/refresh?year=2026"), admin));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<Integer> call : calls) {
                statuses.add(call.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(messages()).anyMatch(m -> m.startsWith("stock.survey.refresh companyId=" + e1));
    }

    /**
     * A regime in the history the code doesn't know: the routine skips that company (the others are calculated); "refresh
     * now" answers 409 error.reform.stock.calculation.failed and does not consume the cooldown.
     */
    @Test
    void unknownRegimeFailsClearlyWithoutConsumingTheCooldown() throws Exception {
        String bad = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO company_tax_regimes (id, deleted, valid_from, created_date, tax_regime, company_id, created_by)"
                + " VALUES (?, 0, '2026-01-01', ?, 'REGIME_FICTICIO', ?, 'it')", bad, Timestamp.valueOf(NOW), e4);

        assertThat(scheduler.runOnce()).hasValueSatisfying(summary -> assertThat(summary.failed()).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_credit_surveys WHERE company_id = ?", Integer.class, e4)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_credit_surveys WHERE company_id = ?", Integer.class, e1)).isEqualTo(1);

        clock.set(LocalDateTime.of(2026, 12, 10, 10, 0));
        expectError(post(SURVEY + "/" + e4 + "/refresh?year=2026"), admin, 409, "error.reform.stock.calculation.failed", null);
        assertThat(jdbc.queryForList("SELECT refresh_requested_at FROM stock_credit_surveys WHERE company_id = ?", Timestamp.class, e4))
                .as("reservation rolled back").allSatisfy(at -> assertThat(at).isNull());
        assertThat(messages()).anyMatch(m -> m.startsWith("stock.survey.company.failed companyId=" + e4));

        jdbc.update("DELETE FROM company_tax_regimes WHERE id = ?", bad);
        clock.set(LocalDateTime.of(2026, 12, 10, 10, 1));
        assertThat(status(it.call(post(SURVEY + "/" + e4 + "/refresh?year=2026"), admin))).as("no cooldown left behind").isEqualTo(200);
    }

    // ================================================================== CE-RN-12, CE-RN-13, CE-RN-15, CSV

    /** CA-CE-11, CA-CE-12, CT-CE-27, CT-CE-31. */
    @Test
    void switchAndClosing() throws Exception {
        scheduler.runOnce();
        JsonNode features = it.ok(get(BASE + "/reform/features"), contabil);
        assertThat(features.at("/stockCredit/enabled").asBoolean()).isTrue();
        assertThat(features.at("/stockCredit/closed").asBoolean()).isFalse();
        assertThat(features.at("/stockCredit/closesOn").asText()).isEqualTo("2027-06-30");

        clock.set(LocalDateTime.of(2027, 6, 30, 23, 59));
        assertThat(status(it.call(statusChange(e1, "NOTIFIED", null, 0), admin))).isEqualTo(200);
        clock.set(LocalDateTime.of(2027, 7, 1, 0, 0));
        expectError(statusChange(e1, "DISCARDED", null, 1), admin, 409, "error.reform.stock.survey.closed", null);
        expectError(post(SURVEY + "/" + e1 + "/refresh?year=2026"), admin, 409, "error.reform.stock.survey.closed", null);
        assertThat(status(it.call(get(SURVEY + "?year=2026"), admin))).isEqualTo(200);
        assertThat(it.ok(get(SURVEY + "/" + e1 + "?year=2026"), admin).get("closed").asBoolean()).isTrue();
        assertThat(it.ok(get(BASE + "/reform/features"), admin).at("/stockCredit/closed").asBoolean()).isTrue();

        properties.setEnabled(false);
        assertThat(it.ok(get(BASE + "/reform/features"), admin).at("/stockCredit/enabled").asBoolean()).isFalse();
        expectError(get(SURVEY + "?year=2026"), admin, 404, "error.reform.stock.disabled", null);
        expectError(get(SURVEY + "/export.csv?year=2026"), admin, 404, "error.reform.stock.disabled", null);
        expectError(get(SURVEY + "/" + e1 + "/history?year=2026"), admin, 404, "error.reform.stock.disabled", null);
    }

    /** CA-CE-13 and CT-CE-25. */
    @Test
    void csv() throws Exception {
        scheduler.runOnce();
        MvcResult result = it.call(get(SURVEY + "/export.csv?year=2026"), admin);
        assertThat(status(result)).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"credito-estoque-2026-20261210.csv\"");
        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        String csv = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).startsWith("Razão social;CNPJ;Regime dez/2026;Regime jan/2027;Classificação;Motivo;Compras 2026 (R$)");
        assertThat(lines[1]).startsWith("Atacado Exemplo Ltda;11.222.333/0001-81;Lucro Presumido;;Hipótese I;")
                .contains(";168000,00;4;4;");
        assertThat(lines[2]).startsWith("'=SOMA(A1) Comércio Ltda;33.444.555/0001-81;");
        // the notice has a ";" (art. 381; Decreto): quoted
        assertThat(lines[lines.length - 1]).startsWith("Observação;\"Levantamento de quem pode ter direito").endsWith("técnico.\"");
        assertThat(csv).endsWith("\r\n");
    }

    // ================================================================== scenario

    /** CT-CE-01 to CT-CE-04 for E1, with notes that must stay out. */
    private void notesOfE1() {
        item(note(e1, "INBOUND", null, LocalDateTime.of(2026, 3, 10, 9, 0), false), "5102", "120000.00", "01", "22030000");
        String may = note(e1, "INBOUND", "AUTHORIZED", LocalDateTime.of(2026, 5, 10, 9, 0), false);
        item(may, "5405", "30000.00", "04", "22021000");
        item(note(e1, "INBOUND", null, LocalDateTime.of(2026, 5, 20, 9, 0), false), "6102", "10000.00", "06", "22030000");
        item(note(e1, "INBOUND", "CANCELED", LocalDateTime.of(2026, 6, 1, 9, 0), false), "5102", "5000.00", "01", "22030000");
        item(note(e1, "INBOUND", null, LocalDateTime.of(2026, 7, 1, 9, 0), false), "5949", "2000.00", "41", "22030000");
        item(note(e1, "INBOUND", null, LocalDateTime.of(2026, 8, 1, 9, 0), true), "5102", "999.00", "01", "22030000");
        item(note(e1, "OUTBOUND", null, LocalDateTime.of(2026, 8, 2, 9, 0), false), "5102", "777.00", "01", "22030000");
        item(note(e1, "INBOUND", null, LocalDateTime.of(2026, 12, 31, 23, 30), false), "1102", "8000.00", null, "84719012");
        item(note(e1, "INBOUND", null, LocalDateTime.of(2027, 1, 1, 0, 10), false), "5102", "4444.00", "01", "22030000");
    }

    private String note(String company, String direction, String status, LocalDateTime issuedAt, boolean deleted) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO fiscal_documents (id, deleted, created_date, created_by, document_type, model, source_system, company_id,"
                        + " xml_file_path, direction, company_role, status, issue_date, metadata_version)"
                        + " VALUES (?, ?, ?, 'it', 'NFE', '55', 'SIEG', ?, 'xml/ficticio.xml', ?, ?, ?, ?, 0)",
                id, deleted, Timestamp.valueOf(NOW), company, direction, "OUTBOUND".equals(direction) ? "ISSUER" : "RECIPIENT",
                status == null ? "AUTHORIZED" : status, Timestamp.valueOf(issuedAt));
        return id;
    }

    private void item(String document, String cfop, String value, String cstPis, String ncm) {
        jdbc.update("INSERT INTO fiscal_document_items (id, document_id, cfop, total_amount, pis_cst, ncm) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), document, cfop, value == null ? null : new java.math.BigDecimal(value), cstPis, ncm);
    }

    private void regime(String company, String regime, LocalDate validFrom) {
        jdbc.update("INSERT INTO company_tax_regimes (id, deleted, valid_from, created_date, tax_regime, company_id, created_by)"
                        + " VALUES (?, 0, ?, ?, ?, ?, 'it')", UUID.randomUUID().toString(),
                validFrom == null ? null : java.sql.Date.valueOf(validFrom), Timestamp.valueOf(NOW), regime, company);
    }

    // ================================================================== helpers

    private MockHttpServletRequestBuilder statusChange(String company, String status, String note, Integer version) throws Exception {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("year", 2026);
        fields.put("status", status);
        fields.put("note", note);
        fields.put("version", version);
        String body = json.writeValueAsString(fields);
        return put(SURVEY + "/" + company + "/status").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void expectError(MockHttpServletRequestBuilder request, String token, int status, String code, String invalidField)
            throws Exception {
        MvcResult result = it.call(request, token);
        assertThat(status(result)).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        assertThat(it.code(result)).isEqualTo(code);
        if (invalidField != null) {
            assertThat(it.body(result).get("invalidField").asText()).isEqualTo(invalidField);
        }
    }

    private static List<String> ids(JsonNode list) {
        List<String> ids = new ArrayList<>();
        list.at("/page/content").forEach(row -> ids.add(row.get("companyId").asText()));
        return ids;
    }

    private static JsonNode row(JsonNode list, String company) {
        for (JsonNode row : list.at("/page/content")) {
            if (row.get("companyId").asText().equals(company)) {
                return row;
            }
        }
        throw new AssertionError("company not in the list");
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
