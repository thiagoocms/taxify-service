package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckContext;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckEngine;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * IBS/CBS reference rates per year (spec 2026-11-27, F1a-0) end to end on the local MySQL: the migrated 2026 row, the
 * console's upsert with its validation, version and history, the firms' reading with the warnings of Recife, the
 * access rules and the conferência's context (only LAW). The 2027 rates are fictitious test numbers. The years
 * 2027-2040 are removed before and after each test; 2026 is never changed.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.platform.admin-logins=it.al.csadmin",
        "taxify.platform.firm-id=" + IbsCbsRateIntegrationTest.PLATFORM_FIRM,
        "taxify.capture-priority.purge-enabled=false",
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false", "taxify.reform.stock-survey.enabled=false"
})
@AutoConfigureMockMvc
class IbsCbsRateIntegrationTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-0000000a1a01";
    private static final String CSADMIN = "it.al.csadmin";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 15, 10, 0);
    private static final String RATES = BASE + "/platform/ibs-cbs-rates";

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock ibsCbsRateTestClock() {
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
    private FiscalCheckEngine engine;

    private ReformItSupport it;
    private String csadmin;
    private String firmAdmin;
    private String blockedUser;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void setUp() throws Exception {
        it = new ReformItSupport(mvc, json, jdbc, passwordEncoder, NOW, "ital");
        it.assumeLocalDatabase();
        clock.set(NOW);
        cleanYears();
        it.removeFirm(PLATFORM_FIRM);
        it.rawFirm(PLATFORM_FIRM, "CS Hub (teste)", null);
        it.user(PLATFORM_FIRM, CSADMIN, "ADMIN", null);
        String firm = it.firm("Escritório Exemplo", "ACTIVE");
        it.user(firm, "admin", "ADMIN", null);
        String blocked = it.firm("Escritório Bloqueado", "BLOCKED");
        it.user(blocked, "fiscal.bloqueado", "EMPLOYEE", "FISCAL");
        csadmin = it.token(CSADMIN);
        firmAdmin = it.token("admin");
        blockedUser = it.token("fiscal.bloqueado");
        logs.list.clear();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        cleanYears();
        it.removeAll();
        it.removeFirm(PLATFORM_FIRM);
    }

    private void cleanYears() {
        jdbc.update("DELETE FROM ibs_cbs_rate_changes WHERE rate_year >= 2027");
        jdbc.update("DELETE FROM ibs_cbs_rates WHERE ano >= 2027");
    }

    // ================================================================== AL-RN-07, AL-RN-02

    /** CT-AL-01 and CA-AL-01: the migrated 2026 row; the engine uses it as before. */
    @Test
    void migrated2026IsLaw() throws Exception {
        MvcResult raw = it.call(get(RATES), csadmin);
        assertThat(raw.getResponse().getContentAsString()).as("4 decimals in the JSON")
                .contains("\"year\":2026,\"cbsRate\":0.9000,\"ibsStateRate\":0.1000,\"ibsMunicipalRate\":0.0000,\"status\":\"LAW\"");
        JsonNode list = it.body(raw);
        JsonNode y2026 = year(list, 2026);
        assertThat(y2026.get("status").asText()).isEqualTo("LAW");
        assertThat(y2026.get("cbsRate").decimalValue()).isEqualByComparingTo("0.9");
        assertThat(y2026.get("generatesFindings").asBoolean()).isTrue();
        assertThat(y2026.get("sourceUrl").asText()).startsWith("https://www.planalto.gov.br/");
        assertThat(y2026.has("version")).isTrue();

        FiscalCheckContext context = engine.contextFor(UUID.randomUUID());
        assertThat(context.ibsCbsRateFor(2026)).hasValueSatisfying(rate ->
                assertThat(rate.getCbsRate()).isEqualByComparingTo("0.9"));
    }

    /** CA-AL-02, CA-AL-03, CT-AL-04, CT-AL-05, CT-AL-15, CT-AL-16: ESTIMATE out of the engine, LAW in, at the next context. */
    @Test
    void estimateThenLaw() throws Exception {
        JsonNode created = it.ok(save(2027, body("ESTIMATE", "8.8000", "0.1000", "0.0000", null, null, null)), csadmin);
        assertThat(created.get("version").asInt()).isZero();
        assertThat(created.get("generatesFindings").asBoolean()).isFalse();
        assertThat(created.get("changedBy").asText()).isEqualTo(CSADMIN);
        assertThat(created.get("changedAt").asText()).isEqualTo("2026-12-15T10:00:00-03:00");
        assertThat(engine.contextFor(UUID.randomUUID()).ibsCbsRateFor(2027)).isEmpty();

        clock.set(NOW.plusMinutes(5));
        JsonNode law = it.ok(save(2027, body("LAW", "8.8", "0.1", "0", "Ato fictício de teste", "https://exemplo.test/ato", 0)), csadmin);
        assertThat(law.get("version").asInt()).isEqualTo(1);
        assertThat(law.get("generatesFindings").asBoolean()).isTrue();
        // read again at the next context: no cache (the other instance would see it the same way)
        assertThat(engine.contextFor(UUID.randomUUID()).ibsCbsRateFor(2027)).hasValueSatisfying(rate ->
                assertThat(rate.getCbsRate()).isEqualByComparingTo("8.8"));

        JsonNode history = it.ok(get(RATES + "/2027/history"), csadmin);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("action").asText()).isEqualTo("UPDATED");
        assertThat(history.get(0).get("statusBefore").asText()).isEqualTo("ESTIMATE");
        assertThat(history.get(0).get("statusAfter").asText()).isEqualTo("LAW");
        assertThat(history.get(0).get("changedBy").asText()).isEqualTo(CSADMIN);
        assertThat(history.get(0).get("changedAt").asText()).isEqualTo("2026-12-15T10:05:00-03:00");
        assertThat(history.get(1).get("action").asText()).isEqualTo("CREATED");
        assertThat(messages()).anyMatch(m -> m.startsWith("ibs.cbs.rate.saved year=2027 action=UPDATED statusBefore=ESTIMATE statusAfter=LAW userId="));
        assertThat(messages()).noneMatch(m -> m.contains("Ato fictício"));
    }

    // ================================================================== AL-RN-03

    /** CA-AL-04, CT-AL-07 to CT-AL-11. */
    @Test
    void validation() throws Exception {
        expectError(save(2027, body("LAW", "8.8", "0.1", "0", "Ato", null, null)), 400, "error.reform.rate.basis.required");
        expectError(save(2027, body("LAW", "8.8", "0.1", "0", "Ato", "http://exemplo.test", null)), 400, "error.reform.rate.basis.required");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ibs_cbs_rates WHERE ano = 2027", Integer.class)).isZero();

        MvcResult fiveDecimals = it.call(save(2027, body("ESTIMATE", "0.12345", "0.1", "0", null, null, null)), csadmin);
        assertThat(status(fiveDecimals)).isEqualTo(400);
        assertThat(it.code(fiveDecimals)).isEqualTo("error.reform.rate.invalid");
        assertThat(it.body(fiveDecimals).get("invalidField").asText()).isEqualTo("cbsRate");
        expectError(save(2027, body("ESTIMATE", "-1", "0.1", "0", null, null, null)), 400, "error.reform.rate.invalid");
        expectError(save(2027, body("ESTIMATE", "100.0001", "0.1", "0", null, null, null)), 400, "error.reform.rate.invalid");
        expectError(save(2025, body("PENDING", null, null, null, null, null, null)), 400, "error.reform.rate.year.invalid");
        expectError(save(2041, body("PENDING", null, null, null, null, null, null)), 400, "error.reform.rate.year.invalid");
        expectError(save(2027, body("LEI", "8.8", "0.1", "0", null, null, null)), 400, "error.reform.rate.status.invalid");

        JsonNode pending = it.ok(save(2027, body("PENDING", "8.8000", null, null, null, null, null)), csadmin);
        assertThat(pending.get("ibsStateRate").isNull()).isTrue();
        assertThat(pending.get("ibsMunicipalRate").isNull()).isTrue();
        assertThat(pending.get("generatesFindings").asBoolean()).isFalse();
    }

    // ================================================================== AL-RN-04

    /** CT-AL-12 and CT-AL-14: creating an existing year; a save without change. */
    @Test
    void createExistingAndSaveWithoutChange() throws Exception {
        int version2026 = jdbc.queryForObject("SELECT version FROM ibs_cbs_rates WHERE ano = 2026", Integer.class);
        MvcResult create2026 = it.call(save(2026, body("LAW", "0.9", "0.1", "0", "x", "https://exemplo.test/ato", null)), csadmin);
        assertThat(status(create2026)).isEqualTo(409);
        assertThat(it.code(create2026)).isEqualTo("error.reform.rate.conflict");
        assertThat(it.body(create2026).get("currentVersion").asInt()).isEqualTo(version2026);

        it.ok(save(2027, body("ESTIMATE", "8.8", "0.1", "0", null, null, null)), csadmin);
        JsonNode same = it.ok(save(2027, body("ESTIMATE", "8.80", "0.10", "0.00", " ", null, 0)), csadmin);
        assertThat(same.get("version").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ibs_cbs_rate_changes WHERE rate_year = 2027", Integer.class)).isEqualTo(1);

        expectError(save(2028, body("ESTIMATE", "8.8", "0.1", "0", null, null, 3)), 404, "error.reform.rate.not.found");
        expectError(get(RATES + "/2031/history"), 404, "error.reform.rate.not.found");
    }

    /** CA-AL-05 and CT-AL-13: two saves with the same version at the same time - one 200 (version 2), one 409. */
    @Test
    void concurrentSavesWithTheSameVersion() throws Exception {
        it.ok(save(2027, body("ESTIMATE", "8.8", "0.1", "0", null, null, null)), csadmin);
        it.ok(save(2027, body("ESTIMATE", "8.7", "0.1", "0", null, null, 0)), csadmin);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<MvcResult>> calls = new ArrayList<>();
            for (String cbs : List.of("8.6", "8.5")) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return it.call(save(2027, body("ESTIMATE", cbs, "0.1", "0", null, null, 1)), csadmin);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<MvcResult> call : calls) {
                MvcResult result = call.get();
                statuses.add(status(result));
                if (status(result) == 409) {
                    assertThat(it.body(result).get("currentVersion").asInt()).isEqualTo(2);
                } else {
                    assertThat(it.body(result).get("version").asInt()).isEqualTo(2);
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ibs_cbs_rate_changes WHERE rate_year = 2027 AND action = 'UPDATED'",
                Integer.class)).isEqualTo(2);
        assertThat(messages()).anyMatch(m -> m.startsWith("ibs.cbs.rate.conflict year=2027 userId="));
    }

    // ================================================================== AL-RN-05, AL-RN-06, access

    /** CA-AL-06, CT-AL-03, CT-AL-17 (JVM zone apart, the Recife clock decides). */
    @Test
    void warningsFollowTheRecifeDate() throws Exception {
        JsonNode december = it.ok(get(BASE + "/reform/ibs-cbs-rates"), firmAdmin);
        assertThat(december.get("warnings")).hasSize(1);
        assertThat(december.at("/warnings/0/year").asInt()).isEqualTo(2027);
        assertThat(december.at("/warnings/0/type").asText()).isEqualTo("NO_LAW");
        assertThat(december.at("/warnings/0/text").asText())
                .isEqualTo("2027: sem alíquota oficial cadastrada. A conferência não compara as alíquotas de IBS/CBS das notas desse ano.");
        assertThat(year(december, 2026).has("version")).as("no version for the firms").isFalse();
        assertThat(year(december, 2026).has("changedBy")).isFalse();

        clock.set(LocalDateTime.of(2026, 12, 31, 23, 59));
        assertThat(years(it.ok(get(BASE + "/reform/ibs-cbs-rates"), firmAdmin).get("warnings"))).containsExactly(2027);
        clock.set(LocalDateTime.of(2027, 1, 1, 0, 0));
        assertThat(years(it.ok(get(RATES), csadmin).get("warnings"))).containsExactly(2027, 2028);
        clock.set(LocalDateTime.of(2027, 1, 2, 9, 0));
        assertThat(years(it.ok(get(BASE + "/reform/ibs-cbs-rates"), firmAdmin).get("warnings"))).containsExactly(2027, 2028);
    }

    /** CA-AL-08, CT-AL-18, CT-AL-19: the firm's ADMIN gets 403 on the console; a blocked firm still reads. */
    @Test
    void accessRules() throws Exception {
        assertThat(status(it.call(get(RATES), firmAdmin))).isEqualTo(403);
        assertThat(status(it.call(save(2027, body("ESTIMATE", "8.8", "0.1", "0", null, null, null)), firmAdmin))).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ibs_cbs_rates WHERE ano = 2027", Integer.class)).isZero();
        assertThat(status(it.call(get(RATES + "/2026/history"), firmAdmin))).isEqualTo(403);

        JsonNode blocked = it.ok(get(BASE + "/reform/ibs-cbs-rates"), blockedUser);
        assertThat(blocked.get("years")).isNotEmpty();
    }

    // ================================================================== helpers

    private MockHttpServletRequestBuilder save(int year, String body) {
        return put(RATES + "/" + year).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(String status, String cbs, String ibsState, String ibsMunicipal, String basis, String url,
                               Integer version) {
        return "{\"status\":" + quoted(status) + ",\"cbsRate\":" + cbs + ",\"ibsStateRate\":" + ibsState
                + ",\"ibsMunicipalRate\":" + ibsMunicipal + ",\"legalBasis\":" + quoted(basis) + ",\"sourceUrl\":" + quoted(url)
                + ",\"version\":" + version + "}";
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private void expectError(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        MvcResult result = it.call(request, csadmin);
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(it.code(result)).isEqualTo(code);
    }

    private static JsonNode year(JsonNode list, int year) {
        for (JsonNode row : list.get("years")) {
            if (row.get("year").asInt() == year) {
                return row;
            }
        }
        throw new AssertionError("year " + year + " not in " + list);
    }

    private static List<Integer> years(JsonNode warnings) {
        List<Integer> years = new ArrayList<>();
        warnings.forEach(warning -> years.add(warning.get("year").asInt()));
        return years;
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
