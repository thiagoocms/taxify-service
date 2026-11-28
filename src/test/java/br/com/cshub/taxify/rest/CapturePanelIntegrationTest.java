package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.config.CapturePanelProperties;
import br.com.cshub.taxify.config.CapturePriorityProperties;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.FiscalXmlSyncScheduler;
import br.com.cshub.taxify.service.capture.CaptureEntitlement;
import br.com.cshub.taxify.service.capture.CapturePanelQueryService;
import br.com.cshub.taxify.service.capture.CapturePriorityService;
import br.com.cshub.taxify.service.capture.CaptureStageRecorder;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Painel da captura de XML (spec 2026-11-27) de ponta a ponta: endpoints do escritório e da plataforma, situação
 * calculada, "Sincronizar agora" com os limites, a fila do agendador com prioridade, consultas fixas e o endpoint
 * obsoleto. Cenário base da seção 6 (fictício): hoje é 2026-11-27 10:00 em Recife; escritório A com a Carla (ADMIN), a
 * Ana (FISCAL, carteira X e Y) e o Davi (MANAGEMENT); empresas X, Y, Z e Z2 (filial de Z); escritório B bloqueado com
 * W; o csadmin da plataforma. CNPJs fictícios com dígito válido; e-mails @exemplo.test.
 *
 * <p><b>Só roda contra um MySQL local</b> ({@code DB_HOST=localhost}); cada teste confere a URL real da conexão.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.platform.admin-logins=it.cp.csadmin",
        "taxify.platform.firm-id=" + CapturePanelIntegrationTest.PLATFORM_FIRM,
        "taxify.capture-panel.platform-enabled=true",
        "taxify.capture-priority.purge-enabled=false",
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.pendency-alerts.enabled=false", "taxify.password.reset-purge-enabled=false",
        // the SIEG capture "on" for the panel and the button, but its cron never fires: the test runs the ticks itself
        "fiscal-xml.sync.enabled=true", "fiscal-xml.sync.cron=-", "fiscal-xml.sync.batch-size=2",
        "fiscal-xml.sync.reference-date=2026-11-27",
        "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "sefaz.dist.ufs=PE", "nfse.adn.enabled=false", "audit.enabled=false",
        "pendencies.enabled=false", "acessorias.sync-enabled=false", "billing.sync-enabled=false",
        "efisco.sync.enabled=false", "efisco.certidao.enabled=false"
})
@AutoConfigureMockMvc
class CapturePanelIntegrationTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-0000000c0de1";
    private static final String BASE = "/api/v1";
    private static final String PASSWORD = "SenhaDeTeste2026";
    private static final String CSADMIN = "it.cp.csadmin";
    private static final Random RANDOM = new Random();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 10, 0);

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock capturePanelTestClock() {
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
    private MutableClock clock;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CapturePanelProperties panelProperties;
    @Autowired
    private CapturePriorityProperties priorityProperties;
    @Autowired
    private FiscalXmlSyncProperties syncProperties;
    @Autowired
    private CaptureEntitlement entitlement;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private FiscalXmlSyncControlRepository controlRepository;
    @Autowired
    private FirmAccessService firmAccessService;
    @Autowired
    private CaptureStageRecorder stageRecorder;
    @Autowired
    private CapturePriorityService priorityService;

    private final List<String> firms = new CopyOnWriteArrayList<>();
    private final String prefix = "itcp" + Integer.toHexString(RANDOM.nextInt(0xfffff)) + ".";
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    // the base scenario
    private String firmA;
    private String firmB;
    private String x;
    private String y;
    private String z;
    private String z2;
    private String w;
    private String planSieg;
    private String carla;
    private String ana;
    private String davi;
    private String adminB;

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + connection);
        clock.set(NOW);
        panelProperties.setPlatformEnabled(true);
        priorityProperties.setEnabled(true);
        priorityProperties.setSlotsPerTick(1);
        syncProperties.setEnabled(true);
        syncProperties.setCron("0 0/5 * * * *");
        syncProperties.setBatchSize(2);
        removeFirm(PLATFORM_FIRM);
        logs.list.clear();
        logs.start();
        rootLogger.addAppender(logs);

        // CS Hub: the platform firm (exempt, no firm_subscription) and its admin
        rawFirm(PLATFORM_FIRM, "CS Hub (teste)", null);
        user(PLATFORM_FIRM, CSADMIN, "ADMIN", null, false);

        firmA = firm("Escritório Exemplo", "ACTIVE");
        firmB = firm("Escritório Beta", "BLOCKED");
        planSieg = plan(firmA, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        String root = digits(8);
        x = company(firmA, "Têxtil Exemplo Ltda", cnpj(digits(8), "0001"), "PE", planSieg);
        y = company(firmA, "Confecção Modelo ME", cnpj(digits(8), "0001"), "PE", planSieg);
        z = company(firmA, "Comércio Teste Ltda", cnpj(root, "0001"), "PE", planSieg);
        z2 = company(firmA, "Comércio Teste Ltda", cnpj(root, "0002"), "PE", planSieg);
        w = company(firmB, "Indústria Fictícia SA", cnpj(digits(8), "0001"), "PE", plan(firmB, PlanServiceEnum.FISCAL_XML_IMPORT,
                PlanServiceEnum.SIEG_INTEGRATION));
        carla = user(firmA, "carla.admin", "ADMIN", null, false);
        ana = user(firmA, "ana.fiscal", "EMPLOYEE", "FISCAL", false);
        davi = user(firmA, "davi.gestao", "EMPLOYEE", "MANAGEMENT", false);
        adminB = user(firmB, "admin.beta", "ADMIN", null, false);
        portfolio(ana, x);
        portfolio(ana, y);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        panelProperties.setPlatformEnabled(true);
        priorityProperties.setEnabled(true);
        priorityProperties.setSlotsPerTick(1);
        priorityProperties.setPerFirmPerDay(10);
        priorityProperties.setMaxOpenPerFirm(5);
        syncProperties.setBatchSize(2);
        syncProperties.setEnabled(true);
        for (String firm : firms) {
            removeFirm(firm);
        }
        firms.clear();
        removeFirm(PLATFORM_FIRM);
    }

    // ================================================================== CP-RN-01 / CP-RN-02: who sees what

    /** CA-CP-01 / CT-CP-38 / CT-CP-40. */
    @Test
    void firmViewScopeByProfile() throws Exception {
        JsonNode carlaView = ok(get(BASE + "/capture-panel"), token("carla.admin"));
        assertThat(ids(carlaView)).containsExactlyInAnyOrder(x, y, z, z2);
        assertThat(carlaView.toString()).doesNotContain(w).doesNotContain("Escritório Beta");
        assertThat(carlaView.at("/page/content/0/firmId").isNull()).as("the firm view never brings the firm").isTrue();

        JsonNode anaView = ok(get(BASE + "/capture-panel"), token("ana.fiscal"));
        assertThat(ids(anaView)).containsExactlyInAnyOrder(x, y);
        long anaTotal = 0;
        for (JsonNode count : anaView.at("/summary/byStatus")) {
            anaTotal += count.asLong();
        }
        assertThat(anaTotal).as("summary only of the portfolio").isEqualTo(2);
        assertThat(status(call(auth(post(BASE + "/capture-panel/companies/" + x + "/sync-now"), token("ana.fiscal")))))
                .as("FISCAL has no sync-now (P-CP-02)").isEqualTo(403);
        assertThat(status(call(auth(get(BASE + "/capture-panel/companies/" + z), token("ana.fiscal")))))
                .as("outside the portfolio").isEqualTo(404);

        assertThat(status(call(auth(get(BASE + "/capture-panel"), token("davi.gestao"))))).isEqualTo(403);
        MvcResult other = call(auth(post(BASE + "/capture-panel/companies/" + w + "/sync-now"), token("carla.admin")));
        assertThat(status(other)).as("another firm's company").isEqualTo(404);
        assertThat(status(call(auth(get(BASE + "/capture-panel/companies/" + w), token("carla.admin"))))).isEqualTo(404);
    }

    /** CT-CP-39: a token of a deleted user is never "every company". */
    @Test
    void aContextWithoutAFirmIsForbidden() throws Exception {
        String token = token("carla.admin");
        jdbc.update("UPDATE users SET deleted = 1 WHERE id = ?", carla);
        int status = status(call(auth(get(BASE + "/capture-panel"), token)));
        assertThat(status).isIn(401, 403);
    }

    /** CA-CP-02 / CT-CP-69: the platform view, its log, the 403 for a firm ADMIN and the 404 when switched off. */
    @Test
    void platformView() throws Exception {
        String csadmin = token(CSADMIN);
        JsonNode view = ok(get(BASE + "/platform/capture-panel?size=100"), csadmin);
        assertThat(ids(view)).contains(x, y, z, z2, w);
        JsonNode z2Row = row(view, z2);
        assertThat(z2Row.get("establishment").asText()).isEqualTo("0002");
        assertThat(z2Row.get("firmName").asText()).isEqualTo("Escritório Exemplo " + prefix);
        assertThat(row(view, z).get("establishment").asText()).isEqualTo("0001");
        for (String document : jdbc.queryForList("SELECT d.number FROM documents d JOIN companies c ON c.document_id = d.id"
                + " WHERE c.accounting_firm_id IN (?, ?)", String.class, firmA, firmB)) {
            assertThat(view.toString()).as("never the full CNPJ").doesNotContain(document);
        }
        assertThat(view.at("/page/content/0/channels/SIEG/nextAttemptEstimate").isNull())
                .as("the platform sees the position only").isTrue();
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .startsWith("capture.panel.viewed scope=platform by=").endsWith("firm=all"));

        JsonNode onlyA = ok(get(BASE + "/platform/capture-panel?firmId=" + firmA), csadmin);
        assertThat(ids(onlyA)).containsExactlyInAnyOrder(x, y, z, z2);

        assertThat(status(call(auth(get(BASE + "/platform/capture-panel"), token("carla.admin"))))).isEqualTo(403);
        JsonNode access = ok(get(BASE + "/firm-access"), csadmin);
        assertThat(access.get("capturePanelPlatformEnabled").asBoolean()).isTrue();
        assertThat(ok(get(BASE + "/firm-access"), token("carla.admin")).get("capturePanelPlatformEnabled").asBoolean()).isFalse();

        panelProperties.setPlatformEnabled(false);
        assertThat(status(call(auth(get(BASE + "/platform/capture-panel"), csadmin)))).isEqualTo(404);
        assertThat(status(call(auth(get(BASE + "/platform/capture-panel/companies/" + x), csadmin)))).isEqualTo(404);
        assertThat(status(call(auth(post(BASE + "/platform/capture-panel/companies/" + x + "/sync-now"), csadmin)))).isEqualTo(404);
        assertThat(ok(get(BASE + "/firm-access"), csadmin).get("capturePanelPlatformEnabled").asBoolean()).isFalse();
    }

    // ================================================================== CP-RN-06 to CP-RN-09: situation and memory

    /** CA-CP-03, CA-CP-04, CA-CP-05, CT-CP-31, CT-CP-62, CA-CP-21 (size 500). */
    @Test
    void situationsReasonsAndFilters() throws Exception {
        control(x, NOW.withHour(8).withMinute(10), 40, 40, 12, 12, NOW.withHour(8).withMinute(10), NOW.toLocalDate());
        control(y, NOW.withHour(9), 200, 180, 3, 3, NOW.withHour(9), NOW.toLocalDate());
        LocalDateTime zChecked = LocalDateTime.of(2026, 11, 26, 8, 0);
        control(z, NOW.minusHours(1), 10, 10, 1, 1, zChecked, NOW.toLocalDate());
        control(z2, null, 0, 0, 0, 0, NOW.minusDays(3), NOW.toLocalDate());
        String carlaToken = token("carla.admin");

        JsonNode view = ok(get(BASE + "/capture-panel"), carlaToken);
        assertThat(row(view, x).get("status").asText()).isEqualTo("EM_DIA");
        JsonNode yRow = row(view, y);
        assertThat(yRow.get("status").asText()).isEqualTo("PENDENTE");
        assertThat(yRow.get("statusReason").asText()).isEqualTo("Faltam 20 NF-e recebidas");
        assertThat(yRow.at("/channels/SIEG/received/memory").asText()).isEqualTo(
                "180 notas vindas do SIEG emitidas de 01/11/2026 a 27/11/2026; o SIEG informou 200 na verificação de 27/11/2026 09:00");
        JsonNode unknownStored = yRow.at("/channels/SIEG/received/storedAnySource");
        assertThat(unknownStored.isNull() || unknownStored.isMissingNode()).as("not known before the next check").isTrue();
        // decision of 2026-11-27: next to "vindas do SIEG / total do SIEG", the notes in Taxify from any source
        jdbc.update("UPDATE capture_period_counts SET stored_any_source_total = 195 WHERE company_id = ? AND side = 'RECEIVED'", y);
        JsonNode yStored = row(ok(get(BASE + "/capture-panel"), carlaToken), y);
        assertThat(yStored.get("status").asText()).as("the situation still comes from SIEG's numbers").isEqualTo("PENDENTE");
        assertThat(yStored.at("/channels/SIEG/received/storedAnySource").asInt()).isEqualTo(195);
        assertThat(yStored.at("/channels/SIEG/received/imported").asInt()).isEqualTo(180);
        assertThat(yStored.at("/channels/SIEG/received/memory").asText()).isEqualTo("180 notas vindas do SIEG (195 no Taxify, de qualquer"
                + " origem) emitidas de 01/11/2026 a 27/11/2026; o SIEG informou 200 na verificação de 27/11/2026 09:00");
        assertThat(yRow.at("/channels/SIEG/verifiedAt").asText()).isEqualTo("2026-11-27T09:00:00-03:00");
        JsonNode zRow = row(view, z);
        assertThat(zRow.get("status").asText()).isEqualTo("ATRASADA");
        assertThat(zRow.get("statusReason").asText()).isEqualTo("Sem verificação completa há 26 h");
        JsonNode z2Row = row(view, z2);
        assertThat(z2Row.get("status").asText()).isEqualTo("NUNCA_TENTADA");
        assertThat(z2Row.get("statusReason").asText()).startsWith("Ainda não foi atendida pela fila. Posição: ");
        assertThat(z2Row.at("/channels/SIEG/queuePosition").isInt()).isTrue();
        assertThat(view.at("/summary/byStatus/EM_DIA").asLong()).isEqualTo(1);
        assertThat(view.at("/summary/byStatus/PENDENTE").asLong()).isEqualTo(1);
        // default order: the worst first
        assertThat(view.at("/page/content/0/status").asText()).isEqualTo("NUNCA_TENTADA");

        // CT-CP-62: accent- and case-insensitive search
        assertThat(ids(ok(get(BASE + "/capture-panel?busca=textil"), carlaToken))).containsExactly(x);
        // filters on the summary cards
        assertThat(ids(ok(get(BASE + "/capture-panel?situacao=PENDENTE&situacao=ATRASADA"), carlaToken))).containsExactlyInAnyOrder(y, z);
        assertThat(ids(ok(get(BASE + "/capture-panel?tipo=NFE_RECEBIDAS"), carlaToken))).contains(y).doesNotContain(x);
        assertThat(ids(ok(get(BASE + "/capture-panel?sort=name"), carlaToken)).getFirst()).isIn(z, z2);
        assertThat(status(call(auth(get(BASE + "/capture-panel?sort=whatever"), carlaToken)))).isEqualTo(400);

        // CT-CP-31: an inactive company is out of the list and the summary unless asked
        jdbc.update("UPDATE companies SET active = 0 WHERE id = ?", y);
        JsonNode withoutY = ok(get(BASE + "/capture-panel"), carlaToken);
        assertThat(ids(withoutY)).doesNotContain(y);
        assertThat(withoutY.at("/summary/byStatus/PENDENTE").asLong()).isZero();
        JsonNode withY = ok(get(BASE + "/capture-panel?incluirInativas=true"), carlaToken);
        assertThat(ids(withY)).contains(y);
        assertThat(row(withY, y).get("active").asBoolean()).isFalse();

        // CT-CP-60 / CA-CP-21: size above the maximum
        MvcResult big = call(auth(get(BASE + "/capture-panel?size=101"), carlaToken));
        assertThat(status(big)).isEqualTo(400);
        assertThat(code(big)).isEqualTo("error.generic.bad.request");
        assertThat(status(call(auth(get(BASE + "/capture-panel?size=500"), carlaToken)))).isEqualTo(400);
        // a nonsense page is a 400, a far page an empty one - never a 500
        assertThat(status(call(auth(get(BASE + "/capture-panel?page=2000000000&size=100"), carlaToken)))).isEqualTo(400);
        JsonNode far = ok(get(BASE + "/capture-panel?page=99999&size=100"), carlaToken);
        assertThat(far.at("/page/content").size()).isZero();
    }

    /**
     * CA-CP-07 / CA-CP-08 / CT-CP-14: SIEG answers 503 (then 400 with a CNPJ and a token in the body) on the received
     * side - recorded with the safe code only; then a success resolves it ("última falha ... (resolvida)").
     */
    @Test
    void failureRecordedByTheSchedulerAndResolved() throws Exception {
        othersAttemptedRecently();
        // checked yesterday: a side at 0 checked today would be up to date for 12 h (A3) and never reach SIEG
        control(x, null, 0, 0, 0, 0, NOW.minusDays(1), NOW.toLocalDate());
        clock.set(NOW.withHour(9).withMinute(40));
        RecordingProvider provider = new RecordingProvider();
        provider.failWith = new SiegIntegrationException("error.sieg.integration.failed",
                "corpo 12345678000190 token=abc123", 503);
        provider.only = Set.of(x);
        scheduler(provider, 1).syncAllCompanies();

        Map<String, Object> stage = jdbc.queryForMap("SELECT * FROM capture_stage_status WHERE company_id = ? AND stage = 'SIEG_RECEIVED'", x);
        assertThat(stage.get("failure_code")).isEqualTo("SIEG_UNAVAILABLE");
        assertThat(stage.get("provider_status")).isEqualTo("503");
        assertThat(((Number) stage.get("consecutive_failures")).intValue()).isEqualTo(1);
        clock.set(NOW);
        String carlaToken = token("carla.admin");
        JsonNode xRow = row(ok(get(BASE + "/capture-panel"), carlaToken), x);
        assertThat(xRow.get("status").asText()).isEqualTo("COM_ERRO");
        assertThat(xRow.get("statusReason").asText()).isEqualTo("O SIEG respondeu com erro (HTTP 503). A próxima volta tenta de novo.");
        assertThat(xRow.at("/lastFailure/stage").asText()).isEqualTo("SIEG_RECEIVED");
        assertThat(xRow.at("/lastFailure/resolved").asBoolean()).isFalse();

        // CA-CP-08: 400 with sensitive text - SIEG_REJECTED / 400, nothing of it in the table, the answer or the log
        clock.set(NOW.plusMinutes(5));
        jdbc.update("UPDATE fiscal_xml_sync_control SET last_attempt_at = NULL WHERE company_id = ?", x);
        provider.failWith = new SiegIntegrationException("error.sieg.integration.failed", "corpo 12345678000190 token=abc123", 400);
        scheduler(provider, 1).syncAllCompanies();
        Map<String, Object> rejected = jdbc.queryForMap("SELECT * FROM capture_stage_status WHERE company_id = ? AND stage = 'SIEG_RECEIVED'", x);
        assertThat(rejected.get("failure_code")).isEqualTo("SIEG_REJECTED");
        assertThat(rejected.get("provider_status")).isEqualTo("400");
        assertThat(rejected.toString()).doesNotContain("12345678000190").doesNotContain("abc123");
        String detail = ok(get(BASE + "/capture-panel/companies/" + x), carlaToken).toString();
        assertThat(detail).doesNotContain("12345678000190").doesNotContain("abc123");
        assertThat(logs.list.stream().filter(event -> event.getLoggerName().startsWith("br.com.cshub.taxify.service.capture"))
                .map(ILoggingEvent::getFormattedMessage)).allSatisfy(message ->
                assertThat(message).doesNotContain("12345678000190").doesNotContain("abc123"));

        // the next attempt succeeds at 22:00 - resolved, counter back to 0, the failure kept
        clock.set(NOW.withHour(22));
        jdbc.update("UPDATE fiscal_xml_sync_control SET last_attempt_at = NULL WHERE company_id = ?", x);
        provider.failWith = null;
        scheduler(provider, 1).syncAllCompanies();
        Map<String, Object> resolved = jdbc.queryForMap("SELECT * FROM capture_stage_status WHERE company_id = ? AND stage = 'SIEG_RECEIVED'", x);
        assertThat(resolved.get("last_outcome")).isEqualTo("SUCCESS");
        assertThat(((Number) resolved.get("consecutive_failures")).intValue()).isZero();
        assertThat(resolved.get("last_failure_at")).isNotNull();
        JsonNode after = ok(get(BASE + "/capture-panel/companies/" + x), carlaToken);
        assertThat(after.get("status").asText()).isNotEqualTo("COM_ERRO");
        assertThat(after.at("/lastFailure/resolved").asBoolean()).isTrue();
        assertThat(after.at("/lastFailure/memory").asText()).isEqualTo("última falha em 27/11 10:05 (resolvida)");
        assertThat(after.get("stages").isArray()).isTrue();
    }

    /** CA-CP-09, CA-CP-10, CT-CP-21, CT-CP-27, CA-CP-11: the SEFAZ channel. */
    @Test
    void sefazChannel() throws Exception {
        String sefazPlan = plan(firmA, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION, PlanServiceEnum.SEFAZ_INTEGRATION);
        jdbc.update("UPDATE subscriptions SET plan_id = ? WHERE company_id IN (?, ?, ?, ?)", sefazPlan, x, y, z, z2);
        control(x, NOW.withHour(8), 40, 40, 12, 12, NOW.withHour(8), NOW.toLocalDate());
        String carlaToken = token("carla.admin");

        // X: no certificate of the root
        JsonNode xRow = row(ok(get(BASE + "/capture-panel"), carlaToken), x);
        assertThat(xRow.at("/channels/SEFAZ/status").asText()).isEqualTo("COM_ERRO");
        assertThat(xRow.at("/channels/SEFAZ/failureCode").asText()).isEqualTo("CERT_MISSING");
        assertThat(xRow.at("/channels/SIEG/status").asText()).isEqualTo("EM_DIA");
        assertThat(xRow.get("status").asText()).as("the worst channel (CA-CP-11)").isEqualTo("COM_ERRO");
        assertThat(ids(ok(get(BASE + "/capture-panel?canal=SIEG&situacao=EM_DIA"), carlaToken))).contains(x);

        // CA-CP-09: expired on 20/11/2026 with last_status ERRO
        certificate(x, LocalDateTime.of(2026, 11, 20, 23, 59));
        sefazControl(x, NOW.minusMinutes(45), "ERRO", "Certificado vencido em 20/11/2026. CNPJ 12345678000190");
        // QA B-1: the stage recorded CERT_EXPIRED 3 times, never a success - the same text in every place
        jdbc.update("INSERT INTO capture_stage_status (id, company_id, stage, last_outcome, last_outcome_at, last_failure_at, failure_code,"
                        + " consecutive_failures, deleted, created_by, created_date) VALUES (?, ?, 'SEFAZ_DIST', 'FAILURE', ?, ?,"
                        + " 'CERT_EXPIRED', 3, 0, 'it', ?)", UUID.randomUUID().toString(), x, Timestamp.valueOf(NOW.minusMinutes(45)),
                Timestamp.valueOf(NOW.minusMinutes(45)), Timestamp.valueOf(NOW));
        String expired = "O certificado A1 venceu em 20/11/2026.";
        xRow = row(ok(get(BASE + "/capture-panel"), carlaToken), x);
        assertThat(xRow.at("/channels/SEFAZ/reason").asText()).isEqualTo(expired);
        assertThat(xRow.get("statusReason").asText()).isEqualTo(expired);
        assertThat(xRow.at("/channels/SEFAZ/failureMessage").asText()).isEqualTo(expired);
        assertThat(xRow.at("/lastFailure/message").asText()).isEqualTo(expired);
        assertThat(xRow.at("/lastFailure/explainsStatus").asBoolean()).as("shown once in the list").isTrue();
        assertThat(xRow.at("/lastFailure/memory").asText()).isEqualTo("Falhou 3 vezes seguidas; sem sucesso registrado");
        JsonNode xDetail = ok(get(BASE + "/capture-panel/companies/" + x), carlaToken);
        assertThat(xDetail.at("/stages/0/stage").asText()).isEqualTo("SEFAZ_DIST");
        assertThat(xDetail.at("/stages/0/failureMessage").asText()).isEqualTo(expired);
        assertThat(xDetail.toString()).doesNotContain("venceu em .");

        // CA-CP-10: ERRO recorded before the panel (no stage row), certificate valid - LEGACY_ERROR, never last_detail
        certificate(z, LocalDateTime.of(2027, 3, 15, 12, 0));
        sefazControl(z, NOW.minusMinutes(45), "ERRO", "detalhe antigo sigiloso");
        JsonNode view = ok(get(BASE + "/capture-panel"), carlaToken);
        assertThat(row(view, z).at("/channels/SEFAZ/failureCode").asText()).isEqualTo("LEGACY_ERROR");
        assertThat(view.toString()).doesNotContain("detalhe antigo sigiloso").doesNotContain("12345678000190");

        // CT-CP-21: Z2 has no certificate, the head office Z has one until 15/03/2027
        JsonNode z2Sefaz = row(view, z2).at("/channels/SEFAZ");
        assertThat(z2Sefaz.at("/certificate/present").asBoolean()).isTrue();
        assertThat(z2Sefaz.at("/certificate/fromHeadOffice").asBoolean()).isTrue();
        assertThat(z2Sefaz.at("/certificate/validUntil").asText()).isEqualTo("2027-03-15");
        assertThat(z2Sefaz.at("/certificate/expiringSoon").asBoolean()).isFalse();

        // CT-CP-27: SEFAZ_DIST_UFS=PE, company from PB
        jdbc.update("UPDATE companies SET uf = 'PB' WHERE id = ?", y);
        JsonNode ySefaz = row(ok(get(BASE + "/capture-panel"), carlaToken), y).at("/channels/SEFAZ");
        assertThat(ySefaz.get("status").asText()).isEqualTo("FORA_DO_PLANO");
        assertThat(ySefaz.get("reason").asText()).isEqualTo("UF fora da captura SEFAZ automática");
    }

    /** CT-CP-57 / CA-CP-22 and CT-CP-58: last note per source and the recovery in progress, in the detail. */
    @Test
    void detailLastDocumentsAndRecovery() throws Exception {
        invoice(x, "SIEG", LocalDateTime.of(2026, 11, 27, 8, 11));
        invoice(x, "SIEG", LocalDateTime.of(2026, 11, 20, 8, 0));
        invoice(x, "SEFAZ_DFE", LocalDateTime.of(2026, 11, 26, 15, 15)); // stored as the new key, shown with the legacy name
        recovery(x, LocalDate.of(2026, 10, 1));
        recovery(x, LocalDate.of(2026, 11, 1));

        JsonNode detail = ok(get(BASE + "/capture-panel/companies/" + x), token("carla.admin"));
        assertThat(detail.at("/lastDocuments/nfe/SIEG").asText()).isEqualTo("2026-11-27T08:11:00-03:00");
        assertThat(detail.at("/lastDocuments/nfe/SEFAZ_DIST").asText()).isEqualTo("2026-11-26T15:15:00-03:00");
        assertThat(detail.at("/lastDocuments/nfse/ADN").isNull()).as("Nunca").isTrue();
        assertThat(detail.get("recoveringMonths")).extracting(JsonNode::asText).containsExactly("2026-10", "2026-11");
        assertThat(detail.at("/memory/recovery").asText()).isEqualTo("Recuperando 2026-10 e 2026-11");
        // the list does not carry the detail
        assertThat(row(ok(get(BASE + "/capture-panel"), token("carla.admin")), x).get("lastDocuments").isNull()).isTrue();
    }

    /** CA-CP-12 / CT-CP-30 / CT-CP-50: the blocked firm B. */
    @Test
    void blockedFirm() throws Exception {
        JsonNode platform = ok(get(BASE + "/platform/capture-panel?firmId=" + firmB), token(CSADMIN));
        JsonNode wRow = row(platform, w);
        assertThat(wRow.get("status").asText()).isEqualTo("BLOQUEADA");
        assertThat(wRow.at("/channels/SIEG/status").asText()).isEqualTo("BLOQUEADA");
        assertThat(wRow.at("/channels/SEFAZ/status").asText()).isEqualTo("BLOQUEADA");
        assertThat(wRow.at("/channels/ADN/status").asText()).isEqualTo("BLOQUEADA");

        String adminBToken = token("admin.beta");
        JsonNode bView = ok(get(BASE + "/capture-panel"), adminBToken);
        assertThat(row(bView, w).get("status").asText()).isEqualTo("BLOQUEADA");
        assertThat(bView.at("/summary/byStatus/BLOQUEADA").asLong()).isEqualTo(1);
        MvcResult blocked = call(auth(post(BASE + "/capture-panel/companies/" + w + "/sync-now"), adminBToken));
        assertThat(status(blocked)).isEqualTo(403);
        assertThat(code(blocked)).isEqualTo("error.firm.access.blocked");

        MvcResult fromPlatform = call(auth(post(BASE + "/platform/capture-panel/companies/" + w + "/sync-now"), token(CSADMIN)));
        assertThat(status(fromPlatform)).isEqualTo(409);
        assertThat(code(fromPlatform)).isEqualTo("error.capture.priority.firm.blocked");
    }

    // ================================================================== CP-RN-15 to CP-RN-19: "Sincronizar agora"

    /** CA-CP-13, CA-CP-15, CT-CP-43, CT-CP-45, CT-CP-55, CA-CP-17, CT-CP-49. */
    @Test
    void syncNowIdempotenceCooldownAndRefusals() throws Exception {
        control(z, LocalDateTime.of(2026, 11, 27, 9, 0), 10, 10, 1, 1, LocalDateTime.of(2026, 11, 27, 9, 0), NOW.toLocalDate());
        String carlaToken = token("carla.admin");
        clock.set(NOW.plusMinutes(1));

        JsonNode first = accepted(post(BASE + "/capture-panel/companies/" + z + "/sync-now"), carlaToken);
        assertThat(first.get("status").asText()).isEqualTo("PENDING");
        assertThat(first.get("position").asInt()).isZero();
        assertThat(first.get("estimatedAt").asText()).isEqualTo("2026-11-27T10:05:00-03:00");
        JsonNode again = accepted(post(BASE + "/capture-panel/companies/" + z + "/sync-now"), carlaToken);
        assertThat(again.get("requestId").asText()).isEqualTo(first.get("requestId").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE accounting_firm_id = ?", Integer.class, firmA))
                .as("the day's counter does not go up").isEqualTo(1);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("capture.priority.requested company=" + z + " firm=" + firmA + " by=" + carla + " scope=firm"));

        // the panel shows the open request with who asked (same firm)
        JsonNode zRow = row(ok(get(BASE + "/capture-panel"), carlaToken), z);
        assertThat(zRow.at("/channels/SIEG/priorityRequest/requestedByName").asText()).isEqualTo("Pessoa carla.admin Fictícia");
        assertThat(zRow.at("/channels/SIEG/priorityRequest/requestedByPlatform").asBoolean()).isFalse();
        assertThat(zRow.at("/channels/SIEG/queuePosition").asInt()).isZero();

        // served at 10:05 (the scheduler's tick)
        clock.set(NOW.plusMinutes(5));
        RecordingProvider provider = new RecordingProvider();
        scheduler(provider, 2).syncAllCompanies();
        assertThat(jdbc.queryForObject("SELECT status FROM capture_priority_requests WHERE id = ?", String.class,
                first.get("requestId").asText())).isEqualTo("SERVED");
        assertThat(provider.received()).contains(z);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("capture.priority.served company=" + z + " request=" + first.get("requestId").asText() + " outcome=SERVED"));

        // CT-CP-45: 10:30 is inside the 60 min cooldown - retryAt 11:05 at the top of the error body; 11:06 is fine
        clock.set(NOW.plusMinutes(30));
        MvcResult cooldown = call(auth(post(BASE + "/capture-panel/companies/" + z + "/sync-now"), carlaToken));
        assertThat(status(cooldown)).isEqualTo(429);
        assertThat(code(cooldown)).isEqualTo("error.capture.priority.cooldown");
        assertThat(body(cooldown).get("retryAt").asText()).isEqualTo("2026-11-27T11:05:00-03:00");
        clock.set(NOW.plusMinutes(66));
        assertThat(status(call(auth(post(BASE + "/capture-panel/companies/" + z + "/sync-now"), carlaToken)))).isEqualTo(202);

        // CT-CP-55: a request of the platform shows "Pedido pela CS Hub" to the firm
        accepted(post(BASE + "/platform/capture-panel/companies/" + x + "/sync-now"), token(CSADMIN));
        JsonNode xRow = row(ok(get(BASE + "/capture-panel"), carlaToken), x);
        assertThat(xRow.at("/channels/SIEG/priorityRequest/requestedByPlatform").asBoolean()).isTrue();
        assertThat(xRow.at("/channels/SIEG/priorityRequest/requestedByLabel").asText()).startsWith("Pedido pela CS Hub");
        assertThat(xRow.at("/channels/SIEG/priorityRequest/requestedByName").isNull()).isTrue();

        // CA-CP-17 / CT-CP-48: a company without SIEG in the plan
        String noSieg = company(firmA, "Empresa Sem SIEG Ltda", cnpj(digits(8), "0001"), "PE",
                plan(firmA, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION));
        MvcResult notInPlan = call(auth(post(BASE + "/capture-panel/companies/" + noSieg + "/sync-now"), carlaToken));
        assertThat(status(notInPlan)).isEqualTo(409);
        assertThat(code(notInPlan)).isEqualTo("error.capture.priority.not.in.plan");

        // CT-CP-49: switched off
        priorityProperties.setEnabled(false);
        MvcResult off = call(auth(post(BASE + "/capture-panel/companies/" + y + "/sync-now"), carlaToken));
        assertThat(status(off)).isEqualTo(503);
        assertThat(code(off)).isEqualTo("error.capture.priority.unavailable");
    }

    /** CA-CP-16 / CT-CP-46 and CT-CP-47: the daily and the open limits of the firm. */
    @Test
    void firmLimits() throws Exception {
        String carlaToken = token("carla.admin");
        priorityProperties.setMaxOpenPerFirm(2);
        accepted(post(BASE + "/capture-panel/companies/" + x + "/sync-now"), carlaToken);
        accepted(post(BASE + "/capture-panel/companies/" + y + "/sync-now"), carlaToken);
        MvcResult open = call(auth(post(BASE + "/capture-panel/companies/" + z + "/sync-now"), carlaToken));
        assertThat(status(open)).isEqualTo(429);
        assertThat(code(open)).isEqualTo("error.capture.priority.firm.open.limit");
        assertThat(body(open).get("limit").asInt()).isEqualTo(2);

        priorityProperties.setMaxOpenPerFirm(5);
        priorityProperties.setPerFirmPerDay(2);
        clock.set(NOW.withHour(16));
        MvcResult daily = call(auth(post(BASE + "/capture-panel/companies/" + z + "/sync-now"), carlaToken));
        assertThat(status(daily)).isEqualTo(429);
        assertThat(code(daily)).isEqualTo("error.capture.priority.firm.daily.limit");
        assertThat(body(daily).get("limit").asInt()).isEqualTo(2);
        assertThat(body(daily).get("retryAt").asText()).isEqualTo("2026-11-28T00:00:00-03:00");
    }

    /** CA-CP-18 / CT-CP-44: two POSTs at the same time - one open request, both 202 with the same id. */
    @Test
    void concurrentRequestsOpenOneRequest() throws Exception {
        String carlaToken = token("carla.admin");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return call(auth(post(BASE + "/capture-panel/companies/" + z + "/sync-now"), carlaToken));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, executor));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (CompletableFuture<MvcResult> future : calls) {
                MvcResult result = future.get();
                assertThat(status(result)).isEqualTo(202);
                ids.add(body(result).get("requestId").asText());
            }
            assertThat(ids.get(0)).isEqualTo(ids.get(1));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE company_id = ? AND open_key IS NOT NULL",
                    Integer.class, z)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** CA-CP-19 / CT-CP-51 and CT-CP-52: canceled when the firm is blocked before the tick; expired after 24 h. */
    @Test
    void canceledAndExpiredRequests() throws Exception {
        String carlaToken = token("carla.admin");
        clock.set(NOW.plusMinutes(1));
        String zRequest = accepted(post(BASE + "/capture-panel/companies/" + z + "/sync-now"), carlaToken).get("requestId").asText();
        // an old request of Y, from 26/11 09:00 (25 h before the 10:05 tick)
        String yRequest = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO capture_priority_requests (id, company_id, accounting_firm_id, requested_by, requested_scope,"
                + " requested_at, status, open_key, deleted, created_by, created_date) VALUES (?, ?, ?, ?, 'FIRM', ?, 'PENDING', ?, 0, 'it', ?)",
                yRequest, y, firmA, carla, Timestamp.valueOf(LocalDateTime.of(2026, 11, 26, 9, 0)), y, Timestamp.valueOf(NOW));

        // firm A blocked at 10:03
        jdbc.update("UPDATE firm_subscriptions SET status = 'BLOCKED' WHERE accounting_firm_id = ?", firmA);
        clock.set(NOW.plusMinutes(5));
        RecordingProvider provider = new RecordingProvider();
        scheduler(provider, 2).syncAllCompanies();

        assertThat(jdbc.queryForMap("SELECT status, open_key FROM capture_priority_requests WHERE id = ?", zRequest))
                .containsEntry("status", "CANCELED").containsEntry("open_key", null);
        assertThat(jdbc.queryForObject("SELECT status FROM capture_priority_requests WHERE id = ?", String.class, yRequest))
                .isEqualTo("EXPIRED");
        assertThat(provider.received()).doesNotContain(z, y);
        // not served: no cooldown afterwards
        jdbc.update("UPDATE firm_subscriptions SET status = 'ACTIVE' WHERE accounting_firm_id = ?", firmA);
        assertThat(status(call(auth(post(BASE + "/capture-panel/companies/" + z + "/sync-now"), carlaToken)))).isEqualTo(202);
    }

    /** CT-CP-42 / CA-CP-14 and CT-CP-53: the forced count, also when the regular queue reaches the company first. */
    @Test
    void priorityAttemptCountsAgainEvenWhenUpToDate() throws Exception {
        LocalDateTime checked = NOW.withHour(8).withMinute(10);
        othersAttemptedRecently();
        control(x, LocalDateTime.of(2026, 11, 1, 0, 0), 40, 40, 12, 12, checked, NOW.toLocalDate());
        String carlaToken = token("carla.admin");
        accepted(post(BASE + "/capture-panel/companies/" + x + "/sync-now"), carlaToken);
        // no priority slot: the regular queue reaches X first (CT-CP-53)
        priorityProperties.setSlotsPerTick(0);
        RecordingProvider provider = new RecordingProvider();
        provider.only = Set.of(x);
        clock.set(NOW.plusMinutes(5));
        scheduler(provider, 1).syncAllCompanies();

        assertThat(provider.requests).anySatisfy(request -> {
            assertThat(request.companyId().toString()).isEqualTo(x);
            assertThat(request.side()).isEqualTo(FiscalXmlSide.RECEIVED);
            assertThat(request.knownPending()).as("counted again on SIEG").isFalse();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM capture_priority_requests WHERE company_id = ?", String.class, x))
                .isEqualTo("SERVED");
    }

    /** CT-CP-54: batch-size 1 and 3 open requests - the ticks alternate request / queue. */
    @Test
    void batchOfOneAlternates() throws Exception {
        String n1 = company(firmA, "Normal Um Ltda", cnpj(digits(8), "0001"), "PE", planSieg);
        String n2 = company(firmA, "Normal Dois Ltda", cnpj(digits(8), "0001"), "PE", planSieg);
        for (String requested : List.of(x, y, z)) {
            control(requested, NOW.withHour(9), 1, 1, 1, 1, NOW.withHour(9), NOW.toLocalDate());
        }
        control(z2, NOW.withHour(9).withMinute(30), 1, 1, 1, 1, NOW.withHour(9), NOW.toLocalDate());
        String carlaToken = token("carla.admin");
        for (String requested : List.of(x, y, z)) {
            accepted(post(BASE + "/capture-panel/companies/" + requested + "/sync-now"), carlaToken);
        }
        RecordingProvider provider = new RecordingProvider();
        List<Boolean> priorityTicks = new ArrayList<>();
        for (int tick = 0; tick < 6; tick++) {
            LocalDateTime at = NOW.plusMinutes(5L * (tick + 1));
            clock.set(at);
            scheduler(provider, 1).syncAllCompanies();
            // the company the tick reserved: last_attempt_at = the tick's time
            List<String> reserved = jdbc.queryForList("SELECT company_id FROM fiscal_xml_sync_control WHERE last_attempt_at = ?",
                    String.class, Timestamp.valueOf(at));
            assertThat(reserved).as("tick %d", tick).hasSize(1);
            priorityTicks.add(List.of(x, y, z).contains(reserved.getFirst()));
        }
        for (int i = 1; i < priorityTicks.size(); i++) {
            assertThat(priorityTicks.get(i)).as("tick %d alternates", i).isNotEqualTo(priorityTicks.get(i - 1));
        }
        assertThat(priorityTicks.stream().filter(Boolean::booleanValue).count()).isEqualTo(3);
        assertThat(provider.received()).contains(x, y, z);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE accounting_firm_id = ? AND status = 'SERVED'",
                Integer.class, firmA)).isEqualTo(3);
    }

    /** Review: the daily cap holds with concurrent requests of different companies (named lock per firm). */
    @Test
    void firmCapsHoldUnderConcurrency() throws Exception {
        String carlaToken = token("carla.admin");
        priorityProperties.setPerFirmPerDay(2);
        List<String> companies = new ArrayList<>(List.of(x, y, z, z2));
        companies.add(company(firmA, "Concorrente Um Ltda", cnpj(digits(8), "0001"), "PE", planSieg));
        companies.add(company(firmA, "Concorrente Dois Ltda", cnpj(digits(8), "0001"), "PE", planSieg));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(companies.size());
        try {
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (String company : companies) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return status(call(auth(post(BASE + "/capture-panel/companies/" + company + "/sync-now"), carlaToken)));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, executor));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<Integer> future : calls) {
                statuses.add(future.get());
            }
            assertThat(statuses).filteredOn(code -> code == 202).hasSize(2);
            assertThat(statuses).filteredOn(code -> code == 429).hasSize(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE accounting_firm_id = ?",
                    Integer.class, firmA)).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Review: an open SIEG_RECOVERY failure with nothing left to recover is closed as a warning, not COM_ERRO forever. */
    @Test
    void recoveryFailureIsClosedWhenNothingIsLeft() throws Exception {
        control(x, NOW.withHour(8), 1, 1, 1, 1, NOW.withHour(8), NOW.toLocalDate());
        jdbc.update("INSERT INTO capture_stage_status (id, company_id, stage, last_outcome, last_outcome_at, last_failure_at, failure_code,"
                        + " consecutive_failures, deleted, created_by, created_date) VALUES (?, ?, 'SIEG_RECOVERY', 'FAILURE', ?, ?,"
                        + " 'SIEG_TIMEOUT', 10, 0, 'it', ?)", UUID.randomUUID().toString(), x, Timestamp.valueOf(NOW.minusDays(1)),
                Timestamp.valueOf(NOW.minusDays(1)), Timestamp.valueOf(NOW));
        String carlaToken = token("carla.admin");
        assertThat(row(ok(get(BASE + "/capture-panel"), carlaToken), x).get("status").asText()).isEqualTo("COM_ERRO");

        stageRecorder.closeFailure(UUID.fromString(x), br.com.cshub.taxify.enumerated.CaptureStage.SIEG_RECOVERY,
                br.com.cshub.taxify.enumerated.CaptureFailureCode.SIEG_RECOVERY_GAVE_UP);

        Map<String, Object> stage = jdbc.queryForMap("SELECT * FROM capture_stage_status WHERE company_id = ? AND stage = 'SIEG_RECOVERY'", x);
        assertThat(stage.get("last_outcome")).isEqualTo("SUCCESS");
        assertThat(stage.get("warning_code")).isEqualTo("SIEG_RECOVERY_GAVE_UP");
        assertThat(((Number) stage.get("consecutive_failures")).intValue()).isZero();
        JsonNode xRow = row(ok(get(BASE + "/capture-panel"), carlaToken), x);
        assertThat(xRow.get("status").asText()).isEqualTo("EM_DIA");
        assertThat(xRow.at("/lastFailure/resolved").asBoolean()).isTrue();
    }

    /** CT-CP-56: requests older than 90 days are deleted, recent ones stay. */
    @Test
    void purgeAfterNinetyDays() {
        String old = UUID.randomUUID().toString();
        String recent = UUID.randomUUID().toString();
        for (Object[] row : List.of(new Object[]{old, LocalDateTime.of(2026, 8, 25, 10, 0)}, new Object[]{recent, NOW})) {
            jdbc.update("INSERT INTO capture_priority_requests (id, company_id, accounting_firm_id, requested_by, requested_scope,"
                            + " requested_at, status, served_at, deleted, created_by, created_date) VALUES (?, ?, ?, ?, 'FIRM', ?, 'SERVED', ?, 0, 'it', ?)",
                    row[0], x, firmA, carla, Timestamp.valueOf((LocalDateTime) row[1]), Timestamp.valueOf((LocalDateTime) row[1]),
                    Timestamp.valueOf(NOW));
        }
        assertThat(priorityService.purge()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE id = ?", Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM capture_priority_requests WHERE id = ?", Integer.class, recent)).isEqualTo(1);
    }

    // ================================================================== CP-RN-12 / CP-RN-14: queue and cost

    /** CA-CP-06 (scaled), CA-CP-20 / CT-CP-36 / CT-CP-37, CT-CP-34. */
    @Test
    void queueHealthAndLap() throws Exception {
        control(x, LocalDateTime.of(2026, 11, 26, 16, 0), 1, 1, 1, 1, NOW.withHour(9), NOW.toLocalDate());
        control(y, NOW.withHour(9).withMinute(20), 1, 1, 1, 1, NOW.withHour(9), NOW.toLocalDate());
        String csadmin = token(CSADMIN);
        JsonNode queue = ok(get(BASE + "/platform/capture-panel"), csadmin).get("queue");
        assertThat(queue.get("health").asText()).isEqualTo("STALLED");
        assertThat(queue.get("stalledSince").asText()).isEqualTo("2026-11-27T09:20:00-03:00");
        assertThat(queue.get("observedLapMinutes").asLong()).as("27/11 10:00 - 26/11 16:00").isEqualTo(18 * 60);
        assertThat(queue.get("neverAttempted").asInt()).as("Z, Z2 and W's firm is blocked").isEqualTo(2);
        assertThat(queue.get("nextTickAt").asText()).isEqualTo("2026-11-27T10:05:00-03:00");

        jdbc.update("UPDATE fiscal_xml_sync_control SET last_attempt_at = ? WHERE company_id = ?",
                Timestamp.valueOf(NOW.withHour(9).withMinute(55)), y);
        assertThat(ok(get(BASE + "/platform/capture-panel"), csadmin).at("/queue/health").asText()).isEqualTo("RUNNING");
        // CP-RN-12: the firm's ADMIN sees the state only - no platform-wide numbers or times
        JsonNode firmQueue = ok(get(BASE + "/capture-panel"), token("carla.admin")).get("queue");
        assertThat(firmQueue.get("health").asText()).isEqualTo("RUNNING");
        assertThat(firmQueue.get("openPriorityRequests").isNull()).isTrue();
        assertThat(firmQueue.get("observedLapMinutes").isNull()).isTrue();
        assertThat(firmQueue.get("theoreticalLapMinutes").isNull()).isTrue();
        assertThat(firmQueue.get("stalledSince").isNull()).isTrue();
        jdbc.update("UPDATE fiscal_xml_sync_control SET last_attempt_at = ? WHERE company_id = ?",
                Timestamp.valueOf(NOW.withHour(9).withMinute(20)), y);
        JsonNode stalledForFirm = ok(get(BASE + "/capture-panel"), token("carla.admin")).get("queue");
        assertThat(stalledForFirm.get("health").asText()).isEqualTo("STALLED");
        assertThat(stalledForFirm.get("stalledSince").isNull()).isTrue();

        syncProperties.setEnabled(false);
        assertThat(ok(get(BASE + "/platform/capture-panel"), csadmin).at("/queue/health").asText()).isEqualTo("DISABLED");
    }

    /** CT-CP-59 / CA-CP-21: the number of SQL statements does not grow with the number of companies. */
    @Test
    void fixedNumberOfQueries() throws Exception {
        String small = firm("Escritório Pequeno", "ACTIVE");
        String large = firm("Escritório Grande", "ACTIVE");
        String smallPlan = plan(small, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        String largePlan = plan(large, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        bulk(small, smallPlan, 20);
        bulk(large, largePlan, 200);
        user(small, "admin.pequeno", "ADMIN", null, false);
        user(large, "admin.grande", "ADMIN", null, false);
        String smallToken = token("admin.pequeno");
        String largeToken = token("admin.grande");
        ok(get(BASE + "/capture-panel?size=50"), smallToken);
        ok(get(BASE + "/capture-panel?size=50"), largeToken);

        long smallCount = selects(() -> ok(get(BASE + "/capture-panel?size=50"), smallToken));
        long started = System.nanoTime();
        long largeCount = selects(() -> ok(get(BASE + "/capture-panel?size=50"), largeToken));
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(largeCount).as("SELECTs with 200 companies (%d ms) vs 20", millis).isEqualTo(smallCount);
        assertThat(ok(get(BASE + "/capture-panel?size=50"), largeToken).at("/page/totalElements").asLong()).isEqualTo(200);
    }

    // ================================================================== CP-RN-05 / CP-RN-23

    /** CT-CP-63 / CA-CP-25: two overlapping subscriptions - the newer without SIEG wins, for the panel and the scheduler. */
    @Test
    void entitlementIsTheSchedulersOne() {
        String noSieg = plan(firmA, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION);
        jdbc.update("INSERT INTO subscriptions (id, deleted, created_date, created_by, start_date, end_date, company_id, plan_id)"
                        + " VALUES (?, 0, ?, 'it', ?, ?, ?, ?)", UUID.randomUUID().toString(), Timestamp.valueOf(NOW),
                java.sql.Date.valueOf(LocalDate.of(2026, 11, 10)), java.sql.Date.valueOf(LocalDate.of(2027, 12, 31)), y, noSieg);
        LocalDate day = entitlement.schedulerToday();
        assertThat(day).isEqualTo(LocalDate.of(2026, 11, 27));
        var panel = entitlement.currentServices(day);
        for (String company : List.of(x, y, z, z2, w)) {
            boolean scheduler = subscriptionRepository
                    .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                            UUID.fromString(company), day, day)
                    .map(subscription -> PlanServiceEnum.importsFrom(subscription.getPlan().getServices(), PlanServiceEnum.SIEG_INTEGRATION))
                    .orElse(false);
            assertThat(CaptureEntitlement.sieg(panel.get(UUID.fromString(company)))).as(company).isEqualTo(scheduler);
        }
        assertThat(CaptureEntitlement.sieg(panel.get(UUID.fromString(y)))).isFalse();
        assertThat(entitlement.siegEntitled(UUID.fromString(y))).isFalse();
    }

    /** CT-CP-70 / CA-CP-27: the old endpoint answers as before, with Deprecation: true. */
    @Test
    void oldEndpointIsDeprecated() throws Exception {
        MvcResult result = call(auth(get(BASE + "/fiscal-xml-sync-control"), token("ana.fiscal")));
        assertThat(status(result)).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Deprecation")).isEqualTo("true");
        assertThat(json.readTree(result.getResponse().getContentAsString()).isArray()).isTrue();
    }

    // ================================================================== fixtures (fictitious)

    /** Y, Z and Z2 attempted at 09:59 and up to date: the scheduler's queue reaches X first. */
    private void othersAttemptedRecently() {
        for (String other : List.of(y, z, z2)) {
            control(other, NOW.withHour(9).withMinute(59), 1, 1, 1, 1, NOW.withHour(9).withMinute(59), NOW.toLocalDate());
        }
    }

    private FiscalXmlSyncScheduler scheduler(FiscalXmlProvider provider, int batch) {
        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setEnabled(true);
        properties.setProvider("sieg");
        properties.setBatchSize(batch);
        properties.setReferenceDate(LocalDate.of(2026, 11, 27));
        return new FiscalXmlSyncScheduler(companyRepository, controlRepository, subscriptionRepository, Map.of("sieg", provider),
                properties, firmAccessService, null, clock, stageRecorder, priorityService);
    }

    /** A provider that never calls SIEG: records each request; {@code only} limits who it answers. */
    static class RecordingProvider implements FiscalXmlProvider {
        final List<FiscalXmlSyncRequest> requests = Collections.synchronizedList(new ArrayList<>());
        RuntimeException failWith;
        Set<String> only;

        @Override
        public FiscalXmlSyncResult sync(FiscalXmlSyncRequest request) {
            requests.add(request);
            if (failWith != null && (only == null || only.contains(request.companyId().toString()))) {
                throw failWith;
            }
            return new FiscalXmlSyncResult(0, 0, 0);
        }

        List<String> received() {
            return requests.stream().filter(request -> request.side() == FiscalXmlSide.RECEIVED)
                    .map(request -> request.companyId().toString()).toList();
        }
    }

    private String firm(String name, String status) {
        String id = UUID.randomUUID().toString();
        rawFirm(id, name + " " + prefix, status);
        firms.add(id);
        return id;
    }

    private void rawFirm(String id, String name, String status) {
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'it', ?)", id, now, name);
        if (status != null) {
            jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, migrated, source, deleted, created_by, created_date)"
                    + " VALUES (?, ?, ?, 0, 'MANUAL', 0, 'it', ?)", UUID.randomUUID().toString(), id, status, now);
        }
    }

    private String plan(String firm, PlanServiceEnum... services) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO plans (id, deleted, created_date, created_by, name, accounting_firm_id) VALUES (?, 0, ?, 'it', 'Plano Teste', ?)",
                id, Timestamp.valueOf(NOW), firm);
        for (PlanServiceEnum service : services) {
            jdbc.update("INSERT INTO plan_services (plan_id, service) VALUES (?, ?)", id, service.name());
        }
        return id;
    }

    private String company(String firm, String name, String cnpj, String uf, String plan) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', ?, 'CNPJ')",
                document, now, cnpj);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active, uf)"
                + " VALUES (?, 0, ?, 'it', ?, ?, ?, 1, ?)", id, now, firm, document, name, uf);
        if (plan != null) {
            jdbc.update("INSERT INTO subscriptions (id, deleted, created_date, created_by, start_date, end_date, company_id, plan_id)"
                            + " VALUES (?, 0, ?, 'it', ?, ?, ?, ?)", UUID.randomUUID().toString(), now,
                    java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)), java.sql.Date.valueOf(LocalDate.of(2027, 12, 31)), id, plan);
        }
        return id;
    }

    private void bulk(String firm, String plan, int count) {
        for (int i = 0; i < count; i++) {
            String id = company(firm, "Empresa Fictícia " + i, cnpj(digits(8), "0001"), "PE", plan);
            control(id, NOW.minusHours(i % 30), i, i, 1, 1, NOW.minusHours(i % 30), NOW.toLocalDate());
        }
    }

    private String user(String firm, String login, String profile, String accessProfile, boolean deleted) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(NOW);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', ?, 'CPF')",
                document, now, digits(11));
        String fullLogin = CSADMIN.equals(login) ? login : prefix + login;
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login,"
                        + " name, email, password) VALUES (?, ?, ?, 'it', ?, ?, ?, ?, ?, ?, ?)",
                id, deleted, now, profile, firm, document, fullLogin, "Pessoa " + login + " Fictícia",
                login + "." + prefix + "@exemplo.test", passwordEncoder.encode(PASSWORD));
        if (accessProfile != null) {
            jdbc.update("INSERT INTO user_access_profiles (user_id, access_profile) VALUES (?, ?)", id, accessProfile);
        }
        return id;
    }

    private void portfolio(String user, String company) {
        jdbc.update("INSERT INTO users_companies (user_id, company_id, role, deleted, created_date, created_by)"
                + " VALUES (?, ?, 'MEMBER', 0, ?, 'it')", user, company, Timestamp.valueOf(NOW));
    }

    private void control(String company, LocalDateTime lastAttempt, int receivedTotal, int receivedImported, int issuedTotal,
                         int issuedImported, LocalDateTime checkedAt, LocalDate sweptOn) {
        jdbc.update("DELETE FROM capture_period_counts WHERE company_id = ?", company);
        jdbc.update("DELETE FROM fiscal_xml_sync_control WHERE company_id = ?", company);
        jdbc.update("INSERT INTO fiscal_xml_sync_control (id, company_id, deleted, created_by, created_date, last_attempt_at,"
                        + " received_events_checked_on, issued_events_checked_on, service_invoices_checked_on)"
                        + " VALUES (?, ?, 0, 'it', ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), company, Timestamp.valueOf(NOW), lastAttempt == null ? null : Timestamp.valueOf(lastAttempt),
                java.sql.Date.valueOf(sweptOn), java.sql.Date.valueOf(sweptOn), java.sql.Date.valueOf(sweptOn));
        // the SIEG counters live in capture_period_counts (FD-RN-32): November's rows, the window of the month on both sides
        counts(company, "RECEIVED", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), receivedTotal, receivedImported, checkedAt);
        counts(company, "ISSUED", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), issuedTotal, issuedImported, checkedAt);
    }

    private void counts(String company, String side, LocalDate windowStart, LocalDate windowEnd, int providerTotal, int imported,
                        LocalDateTime checkedAt) {
        jdbc.update("INSERT INTO capture_period_counts (id, company_id, channel, document_type, side, period_month, window_start,"
                        + " window_end, provider_total, provider_counted_at, imported_total, imported_counted_at, created_by,"
                        + " created_date, deleted) VALUES (?, ?, 'SIEG', 'NFE', ?, '2026-11', ?, ?, ?, ?, ?, ?, 'it', ?, 0)",
                UUID.randomUUID().toString(), company, side, windowStart == null ? null : java.sql.Date.valueOf(windowStart),
                windowEnd == null ? null : java.sql.Date.valueOf(windowEnd), providerTotal, Timestamp.valueOf(checkedAt), imported,
                Timestamp.valueOf(checkedAt), Timestamp.valueOf(NOW));
    }

    private void certificate(String company, LocalDateTime validUntil) {
        jdbc.update("DELETE FROM company_certificates WHERE company_id = ?", company);
        jdbc.update("INSERT INTO company_certificates (id, company_id, file_path, password_encrypted, valid_until, deleted, created_by,"
                        + " created_date) VALUES (?, ?, 'certificados/ficticio.pfx', 'senha-cifrada-ficticia', ?, 0, 'it', ?)",
                UUID.randomUUID().toString(), company, Timestamp.valueOf(validUntil), Timestamp.valueOf(NOW));
    }

    private void sefazControl(String company, LocalDateTime lastRunAt, String status, String detail) {
        jdbc.update("DELETE FROM sefaz_dist_control WHERE company_id = ?", company);
        jdbc.update("INSERT INTO sefaz_dist_control (id, company_id, ult_nsu, last_run_at, last_status, last_detail, deleted, created_by,"
                        + " created_date) VALUES (?, ?, '000000000000001', ?, ?, ?, 0, 'it', ?)", UUID.randomUUID().toString(), company,
                Timestamp.valueOf(lastRunAt), status, detail, Timestamp.valueOf(NOW));
    }

    private void invoice(String company, String source, LocalDateTime createdDate) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO fiscal_documents (id, deleted, created_date, created_by, document_type, model, source_system, company_id,"
                        + " xml_file_path, direction, company_role, status, metadata_version)"
                        + " VALUES (?, 0, ?, 'it', 'NFE', '55', ?, ?, 'xml/ficticio.xml', 'INBOUND', 'RECIPIENT', 'AUTHORIZED', 0)", id,
                Timestamp.valueOf(createdDate), source, company);
        jdbc.update("INSERT INTO fiscal_document_sources (id, document_id, company_id, source, retrieval_mode, first_seen_at, last_seen_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), id, company, source,
                "SIEG".equals(source) ? "PERIOD" : "UPLOAD".equals(source) ? "NONE" : "CURSOR", Timestamp.valueOf(createdDate),
                Timestamp.valueOf(createdDate));
    }

    private void recovery(String company, LocalDate start) {
        jdbc.update("INSERT INTO recovery_tasks (id, accounting_firm_id, scope_id, company_id, source, period_start, period_end,"
                        + " page_cursor, status, attempts, created_date, deleted, created_by) VALUES (?, ?, ?, ?, 'SIEG_NFE_RECEIVED', ?, ?, 0,"
                        + " 'PENDING', 0, ?, 0, 'it')", UUID.randomUUID().toString(), firmA, UUID.randomUUID().toString(), company,
                java.sql.Date.valueOf(start), java.sql.Date.valueOf(start.plusMonths(1).minusDays(1)), Timestamp.valueOf(NOW));
    }

    private void removeFirm(String firm) {
        List<String> companies = jdbc.queryForList("SELECT id FROM companies WHERE accounting_firm_id = ?", String.class, firm);
        for (String company : companies) {
            for (String table : List.of("capture_priority_requests", "capture_stage_status", "fiscal_xml_sync_control",
                    "sefaz_dist_control", "nfse_adn_control", "company_certificates", "fiscal_document_sources", "fiscal_documents", "capture_period_counts",
                    "recovery_tasks", "subscriptions", "users_companies")) {
                jdbc.update("DELETE FROM " + table + " WHERE company_id = ?", company);
            }
        }
        jdbc.update("DELETE FROM capture_priority_requests WHERE accounting_firm_id = ?", firm);
        jdbc.update("DELETE FROM recovery_tasks WHERE accounting_firm_id = ?", firm);
        List<String> plans = jdbc.queryForList("SELECT id FROM plans WHERE accounting_firm_id = ?", String.class, firm);
        for (String plan : plans) {
            jdbc.update("DELETE FROM subscriptions WHERE plan_id = ?", plan);
            jdbc.update("DELETE FROM plan_services WHERE plan_id = ?", plan);
        }
        jdbc.update("DELETE FROM plans WHERE accounting_firm_id = ?", firm);
        List<Map<String, Object>> users = jdbc.queryForList("SELECT id, document_id FROM users WHERE accounting_firm_id = ?", firm);
        for (Map<String, Object> user : users) {
            for (String table : List.of("pendency_alert_items", "pendency_alert_digests", "user_notification_settings",
                    "password_reset_tokens", "user_access_profiles", "users_companies")) {
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

    /** A fictitious CNPJ with valid check digits: 8-digit root + establishment. */
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

    // ================================================================== HTTP helpers

    private String token(String login) throws Exception {
        String full = CSADMIN.equals(login) ? login : prefix + login;
        String auth = Base64.getEncoder().encodeToString((full + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        MvcResult result = mvc.perform(post(BASE + "/auth").contentType(MediaType.APPLICATION_JSON).content("{\"auth\":\"" + auth + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("login " + login).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult result = call(auth(request, token));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private JsonNode accepted(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult result = call(auth(request, token));
        assertThat(status(result)).as(result.getResponse().getContentAsString()).isEqualTo(202);
        return body(result);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult result) throws Exception {
        JsonNode body = body(result);
        return body.has("code") ? body.get("code").asText() : null;
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static List<String> ids(JsonNode panel) {
        List<String> ids = new ArrayList<>();
        panel.at("/page/content").forEach(row -> ids.add(row.get("companyId").asText()));
        return ids;
    }

    private static JsonNode row(JsonNode panel, String companyId) {
        for (JsonNode row : panel.at("/page/content")) {
            if (row.get("companyId").asText().equals(companyId)) {
                return row;
            }
        }
        throw new AssertionError("company " + companyId + " not in the page");
    }

    @FunctionalInterface
    interface Call {
        void run() throws Exception;
    }

    /** SELECT statements the server ran during the call (Com_select, global - the schedulers are off in this test). */
    private long selects(Call call) throws Exception {
        Function<Void, Long> read = ignored -> Long.parseLong(String.valueOf(
                jdbc.queryForMap("SHOW GLOBAL STATUS LIKE 'Com_select'").get("Value")));
        long before = read.apply(null);
        call.run();
        long after = read.apply(null);
        // the two reads themselves are SHOW statements, not SELECTs
        return after - before;
    }

}
