package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.config.StockSurveyProperties;
import br.com.cshub.taxify.repository.RoutineRunRepository;
import br.com.cshub.taxify.repository.StockSurveySourceRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.reform.StockSurveyCalculator;
import br.com.cshub.taxify.service.reform.StockSurveyScheduler;
import br.com.cshub.taxify.service.routine.RoutineRunReservation;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA da Onda 1 da Reforma (specs 2026-11-27: F1a-0 alíquotas IBS/CBS por ano e F5a levantamento do crédito de estoque)
 * por HTTP real: servidor em porta aleatória, cliente java.net.http, MySQL local. Complementa os testes MockMvc do dev
 * ({@link IbsCbsRateIntegrationTest}, {@link StockCreditSurveyIntegrationTest}) com: conferência real pela rota
 * /product-fiscal-rules/conferencia (só LAW gera achado, virada 2026/2027), console com concorrência por HTTP, avisos na
 * virada do ano, 401/403/404, escritório da plataforma bloqueado, enquadramento completo decidido pelo Tony, CFOPs de
 * compra e bordas do ano, isolamento por carteira (lista, resumo, detalhe, CSV, histórico), injeção de fórmula e de
 * LIKE/SQL, cooldown e situação com requisições simultâneas, encerramento em 01/07/2027, chave desligada, bloqueio, teste
 * grátis, rotina em duas instâncias (06:15 e retry 09:00) e falha de uma empresa sem derrubar o lote. Um @AfterEach
 * varre os logs atrás de dado sensível.
 *
 * <p>Dados fictícios (@exemplo.test, CNPJ fictício válido), apagados no fim. Só roda com DB_HOST=localhost.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxify.platform.admin-logins=it.qa.rf.csadmin",
        "taxify.platform.firm-id=" + ReformWave1QaHttpTest.PLATFORM_FIRM,
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
class ReformWave1QaHttpTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-0000000a1f99";
    private static final String CSADMIN = "it.qa.rf.csadmin";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 10, 6, 15);
    private static final String SURVEY = "/reform/stock-credit";
    private static final String RATES = "/platform/ibs-cbs-rates";

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        MutableClock qaReformClock() {
            return new MutableClock(NOW);
        }
    }

    @LocalServerPort
    private int port;
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
    @Autowired
    private StockSurveySourceRepository sourceRepository;
    @Autowired
    private StockSurveyCalculator calculator;
    @Autowired
    private RoutineRunRepository routineRunRepository;
    @Autowired
    private FirmAccessService firmAccessService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    /** Textos que nunca podem aparecer em log: CNPJ, razão social, login, e-mail, observação, fundamento, token. */
    private final List<String> secrets = new CopyOnWriteArrayList<>();
    private final List<String> companies = new CopyOnWriteArrayList<>();
    private ReformItSupport it;

    record Resp(int status, String raw, java.net.http.HttpHeaders headers, byte[] bytes, ObjectMapper mapper) {
        JsonNode body() {
            return mapper.readTree(raw);
        }

        String code() {
            JsonNode body = body();
            return body.has("code") ? body.get("code").asText() : null;
        }
    }

    @BeforeEach
    void setUp() {
        it = new ReformItSupport(null, json, jdbc, passwordEncoder, NOW, "qarf");
        String url = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        assertThat(url).as("JDBC URL").startsWith("jdbc:mysql://localhost:");
        System.out.println("QA-JDBC-URL " + url.replaceAll("\\?.*", "") + " tz=" + TimeZone.getDefault().getID());
        clock.set(NOW);
        properties.setEnabled(true);
        properties.setRefreshCooldownMinutes(10);
        properties.setStaleMinutes(120);
        properties.setBatchSize(50);
        jdbc.update("DELETE FROM routine_runs WHERE routine = 'STOCK_SURVEY'");
        cleanYears();
        it.removeFirm(PLATFORM_FIRM);
        it.rawFirm(PLATFORM_FIRM, "CS Hub (teste QA)", null);
        it.user(PLATFORM_FIRM, CSADMIN, "ADMIN", null);
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
            properties.setEnabled(true);
            clock.set(NOW);
            jdbc.update("DELETE FROM routine_runs WHERE routine = 'STOCK_SURVEY'");
            cleanYears();
            it.removeAll();
            it.removeFirm(PLATFORM_FIRM);
        }
    }

    private void cleanYears() {
        jdbc.update("DELETE FROM ibs_cbs_rate_changes WHERE rate_year >= 2027");
        jdbc.update("DELETE FROM ibs_cbs_rates WHERE ano >= 2027");
        jdbc.update("DELETE FROM ibs_cbs_rate_changes WHERE rate_year = 2026");
        jdbc.update("UPDATE ibs_cbs_rates SET version = 0 WHERE ano = 2026");
    }

    // ================================================================== F1a-0: só LAW gera achado (conferência real)

    /** CA-AL-01..03, CT-AL-02..07, CT-AL-16: a conferência pela rota real, nota a nota, com a virada do ano. */
    @Test
    void onlyLawGeneratesFindingsInTheRealConference() throws Exception {
        String firm = it.firm("Escritorio Alfa", "ACTIVE");
        String company = company(firm, "Distribuidora Exemplo Ltda", "LUCRO_REAL");
        user(firm, "admin", "ADMIN", null);
        String admin = token("admin");
        String cs = token(CSADMIN);

        String last2026 = ibsNote(company, LocalDateTime.of(2026, 12, 31, 23, 59, 59), "000", "000001", "1.0000", "0.1000", "0.0000", null);
        ibsNote(company, LocalDateTime.of(2026, 6, 10, 9, 0), "000", "000001", "0.9000", "0.1000", "0.0000", null);
        String first2027 = ibsNote(company, LocalDateTime.of(2027, 1, 1, 0, 0, 0), "000", "000001", "5.0000", "0.1000", "0.0000", null);
        ibsNote(company, LocalDateTime.of(2027, 1, 5, 10, 0), "410", "410001", "5.0000", "0.1000", "0.0000", null);
        ibsNote(company, LocalDateTime.of(2027, 1, 6, 10, 0), "000", "000001", "5.0000", "0.1000", "0.0000", "CANCELED");

        // 2026 continua igual (LAW semeado) e 2027 sem linha não gera achado
        assertThat(rateFindings(admin, company)).containsExactly(last2026 + "|pCBS|1|0.9|ERROR|Alíquota de CBS 1% diferente da referência de 2026 (0.9%)");

        // 2027 ESTIMATE: nunca
        Resp estimate = put(RATES + "/2027", cs, rateBody("ESTIMATE", "8.8000", "0.1000", "0.0000", null, null, null));
        assertThat(estimate.status()).as(estimate.raw()).isEqualTo(200);
        assertThat(rateFindings(admin, company)).hasSize(1);
        // 2027 PENDING (só CBS): nunca
        Resp pending = put(RATES + "/2027", cs, rateBody("PENDING", "8.8000", null, null, null, null, 0));
        assertThat(pending.status()).as(pending.raw()).isEqualTo(200);
        assertThat(pending.body().get("generatesFindings").asBoolean()).isFalse();
        assertThat(pending.body().get("ibsStateRate").isNull()).isTrue();
        assertThat(rateFindings(admin, company)).hasSize(1);
        // 2027 LAW: gera, na próxima conferência (sem cache)
        Resp law = put(RATES + "/2027", cs, rateBody("LAW", "8.8", "0.1", "0", "Ato fictício QA nº 1", "https://exemplo.test/ato", 1));
        assertThat(law.status()).as(law.raw()).isEqualTo(200);
        assertThat(law.raw()).contains("\"cbsRate\":8.8000");
        assertThat(rateFindings(admin, company)).containsExactlyInAnyOrder(
                last2026 + "|pCBS|1|0.9|ERROR|Alíquota de CBS 1% diferente da referência de 2026 (0.9%)",
                first2027 + "|pCBS|5|8.8|ERROR|Alíquota de CBS 5% diferente da referência de 2027 (8.8%)");
        // voltar para PENDING tira 2027 do motor
        assertThat(put(RATES + "/2027", cs, rateBody("PENDING", "8.8", "0.1", "0", null, null, 2)).status()).isEqualTo(200);
        assertThat(rateFindings(admin, company)).hasSize(1);
        secrets.add("Ato fictício QA");
    }

    // ================================================================== F1a-0: avisos NO_LAW, leitura dos escritórios

    /** CA-AL-06, CT-AL-03, CT-AL-17, CT-AL-19 (rode também com -Duser.timezone=UTC). */
    @Test
    void noLawWarningsAcrossTheNewYear() throws Exception {
        String firm = it.firm("Escritorio Beta", "BLOCKED");
        user(firm, "fiscal.bloq", "EMPLOYEE", "FISCAL");
        String trialFirm = it.firm("Escritorio Teste", null);
        trial(trialFirm);
        user(trialFirm, "admin.trial", "ADMIN", null);
        String blocked = token("fiscal.bloq");
        String trial = token("admin.trial");
        String cs = token(CSADMIN);

        clock.set(LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        Resp read = get("/reform/ibs-cbs-rates", blocked);
        assertThat(read.status()).as("bloqueado lê").isEqualTo(200);
        assertThat(warningYears(read.body())).containsExactly(2027);
        assertThat(read.body().at("/warnings/0/type").asText()).isEqualTo("NO_LAW");
        assertThat(read.body().at("/warnings/0/text").asText())
                .isEqualTo("2027: sem alíquota oficial cadastrada. A conferência não compara as alíquotas de IBS/CBS das notas desse ano.");
        JsonNode y2026 = read.body().at("/years/0");
        assertThat(y2026.get("year").asInt()).isEqualTo(2026);
        assertThat(y2026.get("status").asText()).isEqualTo("LAW");
        assertThat(y2026.get("generatesFindings").asBoolean()).isTrue();
        assertThat(y2026.has("version")).as("leitura do escritório sem version").isFalse();
        assertThat(y2026.has("changedBy")).as("leitura do escritório sem auditoria").isFalse();
        assertThat(read.raw()).contains("\"cbsRate\":0.9000", "\"ibsStateRate\":0.1000", "\"ibsMunicipalRate\":0.0000");

        clock.set(LocalDateTime.of(2027, 1, 1, 0, 0, 0));
        assertThat(warningYears(get("/reform/ibs-cbs-rates", trial).body())).containsExactly(2027, 2028);
        assertThat(warningYears(get(RATES, cs).body())).containsExactly(2027, 2028);
        assertThat(put(RATES + "/2027", cs, rateBody("ESTIMATE", "8.8", "0.1", "0", null, null, null)).status()).isEqualTo(200);
        assertThat(warningYears(get(RATES, cs).body())).as("ESTIMATE não tira o aviso").containsExactly(2027, 2028);
        assertThat(put(RATES + "/2027", cs, rateBody("LAW", "8.8", "0.1", "0", "Ato fictício QA nº 2", "https://exemplo.test/ato2", 0)).status()).isEqualTo(200);
        assertThat(warningYears(get(RATES, cs).body())).containsExactly(2028);
        assertThat(warningYears(get("/reform/ibs-cbs-rates", blocked).body())).containsExactly(2028);
    }

    // ================================================================== F1a-0: cadastro, version, 409, histórico

    /** CA-AL-04, CA-AL-05, CT-AL-08..15: validação sem gravar nada, 409 com currentVersion, concorrência, histórico. */
    @Test
    void consoleValidationVersionAndHistory() throws Exception {
        String cs = token(CSADMIN);
        expectError(put(RATES + "/2025", cs, rateBody("LAW", "1", "1", "1", "x", "https://exemplo.test", null)), 400, "error.reform.rate.year.invalid");
        expectError(put(RATES + "/2041", cs, rateBody("LAW", "1", "1", "1", "x", "https://exemplo.test", null)), 400, "error.reform.rate.year.invalid");
        Resp fifth = put(RATES + "/2027", cs, rateBody("ESTIMATE", "0.12345", "0.1", "0", null, null, null));
        expectError(fifth, 400, "error.reform.rate.invalid");
        assertThat(fifth.body().get("invalidField").asText()).isEqualTo("cbsRate");
        expectError(put(RATES + "/2027", cs, rateBody("ESTIMATE", "-1", "0.1", "0", null, null, null)), 400, "error.reform.rate.invalid");
        Resp over = put(RATES + "/2027", cs, rateBody("ESTIMATE", "1", "100.0001", "0", null, null, null));
        expectError(over, 400, "error.reform.rate.invalid");
        assertThat(over.body().get("invalidField").asText()).isEqualTo("ibsStateRate");
        expectError(put(RATES + "/2027", cs, rateBody("ESTIMATE", "1", "0.1", null, null, null, null)), 400, "error.reform.rate.invalid");
        expectError(put(RATES + "/2027", cs, rateBody("LAW", "8.8", "0.1", "0", "Ato", null, null)), 400, "error.reform.rate.basis.required");
        expectError(put(RATES + "/2027", cs, rateBody("LAW", "8.8", "0.1", "0", "Ato", "http://exemplo.test/ato", null)), 400, "error.reform.rate.basis.required");
        expectError(put(RATES + "/2027", cs, rateBody("LAW", "8.8", "0.1", "0", "   ", "https://exemplo.test/ato", null)), 400, "error.reform.rate.basis.required");
        expectError(put(RATES + "/2027", cs, rateBody("LEI", "8.8", "0.1", "0", "Ato", "https://exemplo.test/ato", null)), 400, "error.reform.rate.status.invalid");
        expectError(put(RATES + "/2027", cs, rateBody(null, "8.8", "0.1", "0", "Ato", "https://exemplo.test/ato", null)), 400, "error.reform.rate.status.invalid");
        Resp longBasis = put(RATES + "/2027", cs, rateBody("ESTIMATE", "8.8", "0.1", "0", "x".repeat(501), null, null));
        assertThat(longBasis.status()).isEqualTo(400);
        Resp sqlYear = rawPut(RATES + "/2027%27%20OR%201=1", cs, rateBody("ESTIMATE", "1", "1", "1", null, null, null));
        assertThat(sqlYear.status()).as("ano com SQL não é 500").isEqualTo(400);
        Resp broken = rawPut(RATES + "/2027", cs, "{\"status\": \"LAW\", ");
        assertThat(broken.status()).as("JSON quebrado").isEqualTo(400);
        assertThat(broken.raw()).doesNotContain("Exception").doesNotContain("at br.com");
        assertThat(count("SELECT COUNT(*) FROM ibs_cbs_rates WHERE ano >= 2027")).as("nada gravado").isZero();
        assertThat(count("SELECT COUNT(*) FROM ibs_cbs_rate_changes WHERE rate_year >= 2027")).isZero();

        // criação de 2026 (já existe) sem version: 409 com currentVersion 0
        Resp exists = put(RATES + "/2026", cs, rateBody("LAW", "0.9", "0.1", "0", "x", "https://exemplo.test", null));
        expectError(exists, 409, "error.reform.rate.conflict");
        assertThat(exists.body().get("currentVersion").asInt()).isZero();

        // dois admins criando 2028 ao mesmo tempo: um cria, o outro 409
        List<Resp> creations = parallel(4, () -> put(RATES + "/2028", cs, rateBody("ESTIMATE", "7.7", "0.1", "0", null, null, null)));
        assertThat(creations).extracting(Resp::status).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);
        creations.stream().filter(r -> r.status() == 409).forEach(r -> {
            assertThat(r.code()).isEqualTo("error.reform.rate.conflict");
            assertThat(r.body().get("currentVersion").asInt()).isZero();
        });
        assertThat(count("SELECT COUNT(*) FROM ibs_cbs_rate_changes WHERE rate_year = 2028")).isEqualTo(1);

        // edições simultâneas com a mesma version: uma grava, as outras 409; um evento só
        List<Resp> updates = parallel(6, () -> put(RATES + "/2028", cs, rateBody("ESTIMATE", "7.6", "0.1", "0", null, null, 0)));
        assertThat(updates).extracting(Resp::status).filteredOn(s -> s == 200).hasSize(1);
        assertThat(updates).extracting(Resp::status).containsOnly(200, 409);
        updates.stream().filter(r -> r.status() == 409).forEach(r -> assertThat(r.body().get("currentVersion").asInt()).isEqualTo(1));
        assertThat(count("SELECT COUNT(*) FROM ibs_cbs_rate_changes WHERE rate_year = 2028")).isEqualTo(2);

        // gravação igual ao gravado: 200, mesma version, nenhum evento
        Resp same = put(RATES + "/2028", cs, rateBody("ESTIMATE", "7.60", "0.1000", "0", null, null, 1));
        assertThat(same.status()).isEqualTo(200);
        assertThat(same.body().get("version").asInt()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM ibs_cbs_rate_changes WHERE rate_year = 2028")).isEqualTo(2);

        clock.set(NOW.plusHours(4));
        Resp toLaw = put(RATES + "/2028", cs, rateBody("LAW", "7.6", "0.1", "0", "Ato fictício QA nº 3", "https://exemplo.test/ato3", 1));
        assertThat(toLaw.status()).isEqualTo(200);
        JsonNode history = get(RATES + "/2028/history", cs).body();
        assertThat(history).hasSize(3);
        assertThat(history.at("/0/action").asText()).isEqualTo("UPDATED");
        assertThat(history.at("/0/statusBefore").asText()).isEqualTo("ESTIMATE");
        assertThat(history.at("/0/statusAfter").asText()).isEqualTo("LAW");
        assertThat(history.at("/0/changedBy").asText()).isEqualTo(CSADMIN);
        assertThat(history.at("/0/changedAt").asText()).isEqualTo("2026-12-10T10:15:00-03:00");
        assertThat(history.at("/0/legalBasisAfter").asText()).isEqualTo("Ato fictício QA nº 3");
        assertThat(history.at("/2/action").asText()).isEqualTo("CREATED");
        assertThat(history.at("/1/cbsBefore").decimalValue()).isEqualByComparingTo("7.7");
        assertThat(history.at("/1/cbsAfter").decimalValue()).isEqualByComparingTo("7.6");
        expectError(get(RATES + "/2039/history", cs), 404, "error.reform.rate.not.found");
        JsonNode list = get(RATES, cs).body();
        assertThat(list.get("years")).extracting(y -> y.get("year").asInt()).containsExactly(2026, 2028);
        assertThat(list.at("/years/1/version").asInt()).isEqualTo(2);
        secrets.add("Ato fictício QA");
    }

    // ================================================================== F1a-0: acesso

    /** CA-AL-08, CT-AL-18: só o admin da plataforma; plataforma bloqueada não afeta /platform; 401 sem token. */
    @Test
    void consoleAccess() throws Exception {
        String firm = it.firm("Escritorio Gama", "ACTIVE");
        user(firm, "admin", "ADMIN", null);
        user(firm, "fiscal", "EMPLOYEE", "FISCAL");
        user(firm, "gestao", "EMPLOYEE", "MANAGEMENT");
        String body = rateBody("ESTIMATE", "8.8", "0.1", "0", null, null, null);
        for (String path : List.of(RATES, RATES + "/2026/history", "/reform/ibs-cbs-rates", "/reform/features",
                SURVEY + "?year=2026", SURVEY + "/export.csv?year=2026")) {
            assertThat(get(path, null).status()).as("sem token " + path).isEqualTo(401);
        }
        assertThat(put(RATES + "/2027", null, body).status()).isEqualTo(401);
        assertThat(rawPut(RATES + "/2027", "eyJhbGciOiJIUzI1NiJ9.e30.invalido", body).status()).isEqualTo(401);
        for (String login : List.of("admin", "fiscal", "gestao")) {
            String token = token(login);
            assertThat(get(RATES, token).status()).as(login).isEqualTo(403);
            assertThat(get(RATES + "/2026/history", token).status()).as(login).isEqualTo(403);
            assertThat(put(RATES + "/2027", token, body).status()).as(login).isEqualTo(403);
            assertThat(get("/reform/ibs-cbs-rates", token).status()).as(login).isEqualTo(200);
        }
        assertThat(count("SELECT COUNT(*) FROM ibs_cbs_rates WHERE ano = 2027")).as("403 não grava").isZero();

        // o escritório da plataforma bloqueado não afeta o console
        jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, migrated, source, deleted, created_by, created_date)"
                + " VALUES (?, ?, 'BLOCKED', 0, 'MANUAL', 0, 'qa', ?)", UUID.randomUUID().toString(), PLATFORM_FIRM, Timestamp.valueOf(NOW));
        String cs = token(CSADMIN);
        assertThat(get(RATES, cs).status()).isEqualTo(200);
        Resp saved = put(RATES + "/2027", cs, body);
        assertThat(saved.status()).as(saved.raw()).isEqualTo(200);
        assertThat(get(RATES + "/2027/history", cs).status()).isEqualTo(200);
    }

    // ================================================================== F5a: enquadramento e compras

    /** CA-CE-01..04, CA-CE-14, CT-CE-01..12, CT-CE-32: enquadramento do Tony, CFOP de compra, bordas do ano, cobertura. */
    @Test
    void classificationPurchasesAndCoverage() throws Exception {
        String firm = it.firm("Escritorio Delta", "ACTIVE");
        user(firm, "admin", "ADMIN", null);
        String presumed = company(firm, "Presumido Exemplo Ltda", null);
        regime(presumed, "LUCRO_PRESUMIDO", LocalDate.of(2025, 1, 1));
        String real = company(firm, "Real Exemplo SA", null);
        regime(real, "LUCRO_REAL", LocalDate.of(2026, 1, 1));
        String simples = company(firm, "Simples Exemplo ME", null);
        regime(simples, "SIMPLES_NACIONAL", null);
        String mei = company(firm, "Mei Exemplo", "MEI");
        String presumedToSimples = company(firm, "Presumido Vira Simples Ltda", null);
        regime(presumedToSimples, "LUCRO_PRESUMIDO", LocalDate.of(2025, 1, 1));
        regime(presumedToSimples, "SIMPLES_NACIONAL", LocalDate.of(2027, 1, 1));
        String realToMei = company(firm, "Real Vira Mei Ltda", null);
        regime(realToMei, "LUCRO_REAL", null);
        regime(realToMei, "MEI", LocalDate.of(2027, 1, 1));
        String presumedToReal = company(firm, "Presumido Vira Real Ltda", null);
        regime(presumedToReal, "LUCRO_PRESUMIDO", LocalDate.of(2025, 1, 1));
        regime(presumedToReal, "LUCRO_REAL", LocalDate.of(2027, 1, 1));
        String none = company(firm, "Sem Regime Ltda", null);
        String other = company(firm, "Outro Regime Ltda", "OTHER");
        String fromDecember = company(firm, "Vira Presumido Em Dezembro Ltda", null);
        regime(fromDecember, "SIMPLES_NACIONAL", null);
        regime(fromDecember, "LUCRO_PRESUMIDO", LocalDate.of(2026, 12, 1));
        String simplesFeb = company(firm, "Simples So Em Fevereiro Ltda", null);
        regime(simplesFeb, "LUCRO_PRESUMIDO", LocalDate.of(2025, 1, 1));
        regime(simplesFeb, "SIMPLES_NACIONAL", LocalDate.of(2027, 2, 1));
        String deletedPeriod = company(firm, "Periodo Excluido Ltda", null);
        regime(deletedPeriod, "LUCRO_PRESUMIDO", LocalDate.of(2025, 1, 1));
        String removed = regime(deletedPeriod, "SIMPLES_NACIONAL", LocalDate.of(2027, 1, 1));
        jdbc.update("UPDATE company_tax_regimes SET deleted = 1 WHERE id = ?", removed);
        String historyWins = company(firm, "Historico Vence Cadastro Ltda", "LUCRO_REAL");
        regime(historyWins, "LUCRO_PRESUMIDO", LocalDate.of(2025, 6, 1));
        String complete = company(firm, "Cobertura Completa Ltda", null);
        regime(complete, "LUCRO_REAL", null);
        for (int month = 1; month <= 11; month++) {
            item(note(complete, "INBOUND", null, LocalDateTime.of(2026, month, 3, 8, 0), false), "1556", "10.00", "98", "39269090");
        }

        // compras do Presumido: só CFOP de compra, nota de entrada não cancelada e não excluída, emitida em 2026
        item(note(presumed, "INBOUND", null, LocalDateTime.of(2025, 12, 31, 23, 59, 59), false), "1102", "100.00", "50", "10000001");
        item(note(presumed, "INBOUND", null, LocalDateTime.of(2026, 1, 1, 0, 0, 0), false), "1102", "1000.10", "50", "10000001");
        String feb = note(presumed, "INBOUND", "AUTHORIZED", LocalDateTime.of(2026, 2, 15, 10, 0), false);
        item(feb, "5102", "2000.20", "01", "22030000");
        item(feb, "5949", "9999.99", "49", "22030000");
        item(note(presumed, "INBOUND", null, LocalDateTime.of(2026, 3, 1, 10, 0), false), "6102", "0.01", "01", "22030000");
        String april = note(presumed, "INBOUND", null, LocalDateTime.of(2026, 4, 1, 10, 0), false);
        item(april, "1202", "500.00", "01", "22030000");
        item(april, "3102", "300.00", " 73 ", " 84719012 ");
        item(note(presumed, "INBOUND", null, LocalDateTime.of(2026, 5, 1, 10, 0), false), "5202", "700.00", "01", "22030000");
        item(note(presumed, "INBOUND", "CANCELED", LocalDateTime.of(2026, 6, 1, 10, 0), false), "5102", "5000.00", "01", "22030000");
        item(note(presumed, "INBOUND", null, LocalDateTime.of(2026, 7, 1, 10, 0), true), "5102", "6000.00", "01", "22030000");
        item(note(presumed, "OUTBOUND", null, LocalDateTime.of(2026, 8, 1, 10, 0), false), "5102", "7000.00", "01", "22030000");
        item(note(presumed, "INBOUND", null, LocalDateTime.of(2026, 9, 1, 10, 0), false), "1556", "800.00", "98", "22030000");
        item(note(presumed, "INBOUND", null, LocalDateTime.of(2026, 10, 1, 10, 0), false), "5102", null, null, null);
        item(note(presumed, "INBOUND", null, LocalDateTime.of(2026, 12, 31, 23, 59, 59), false), "2102", "400.00", "", "");
        item(note(presumed, "INBOUND", null, LocalDateTime.of(2027, 1, 1, 0, 0, 0), false), "1102", "123.00", "50", "10000001");

        assertThat(scheduler.runOnce()).isPresent();
        String admin = token("admin");
        Resp listResp = get(SURVEY + "?year=2026&size=200", admin);
        assertThat(listResp.status()).as(listResp.raw()).isEqualTo(200);
        JsonNode list = listResp.body();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(presumed, "HYPOTHESIS_I");
        expected.put(real, "HYPOTHESIS_II_III");
        expected.put(simples, "OUT_OF_SCOPE");
        expected.put(mei, "OUT_OF_SCOPE");
        expected.put(presumedToSimples, "OUT_OF_SCOPE");
        expected.put(realToMei, "OUT_OF_SCOPE");
        expected.put(presumedToReal, "HYPOTHESIS_I");
        expected.put(none, "NO_REGIME");
        expected.put(other, "NO_REGIME");
        expected.put(fromDecember, "HYPOTHESIS_I");
        expected.put(simplesFeb, "HYPOTHESIS_I");
        expected.put(deletedPeriod, "HYPOTHESIS_I");
        expected.put(historyWins, "HYPOTHESIS_I");
        expected.put(complete, "HYPOTHESIS_II_III");
        expected.forEach((id, classification) -> assertThat(row(list, id).get("classification").asText())
                .as(row(list, id).get("companyName").asText()).isEqualTo(classification));
        assertThat(row(list, presumedToSimples).get("classificationReason").asText())
                .isEqualTo("Cadastrada no Simples Nacional/MEI a partir de jan/2027: fora do crédito pelo enquadramento adotado (LC 214/2025, art. 381).");
        assertThat(row(list, realToMei).get("nextYearRegime").asText()).isEqualTo("MEI");
        assertThat(row(list, simples).get("classificationReason").asText())
                .isEqualTo("Simples Nacional/MEI em dez/2026: fora do crédito pelo enquadramento adotado.");
        assertThat(row(list, real).get("classificationReason").asText())
                .isEqualTo("Lucro Real em dez/2026: hipóteses II e III (LC 214/2025, art. 381, II e III); só parte dos bens pode entrar.");
        assertThat(row(list, none).get("classificationReason").asText())
                .isEqualTo("Regime não informado: cadastre o regime da empresa para classificar.");
        assertThat(row(list, presumedToReal).get("nextYearRegime").asText()).isEqualTo("LUCRO_REAL");
        assertThat(row(list, simplesFeb).get("nextYearRegime").isNull()).as("só o período que começa em 01/01/2027").isTrue();
        assertThat(row(list, deletedPeriod).get("nextYearRegime").isNull()).as("período excluído não conta").isTrue();
        assertThat(list.at("/summary/companies").asInt()).isEqualTo(14);
        assertThat(list.at("/summary/byClassification/HYPOTHESIS_I").asInt()).isEqualTo(6);
        assertThat(list.at("/summary/byClassification/HYPOTHESIS_II_III").asInt()).isEqualTo(2);
        assertThat(list.at("/summary/byClassification/OUT_OF_SCOPE").asInt()).isEqualTo(4);
        assertThat(list.at("/summary/byClassification/NO_REGIME").asInt()).isEqualTo(2);
        // ordem padrão CE-RN-11: I, II/III, sem regime, fora; compras desc; razão social
        List<String> order = ids(list);
        assertThat(order.getFirst()).isEqualTo(presumed);
        assertThat(order.subList(6, 8)).containsExactly(complete, real);
        assertThat(order.subList(10, 14)).containsExactlyInAnyOrder(simples, mei, presumedToSimples, realToMei);

        JsonNode detail = get(SURVEY + "/" + presumed + "?year=2026", admin).body();
        Resp detailRaw = get(SURVEY + "/" + presumed + "?year=2026", admin);
        assertThat(detailRaw.raw()).contains("\"yearPurchases\":3700.31");
        assertThat(detail.get("purchaseItems").asInt()).as("1102, 5102, 6102, 3102, 5102 nulo, 2102").isEqualTo(6);
        assertThat(detail.get("inboundInvoices").asInt()).isEqualTo(6);
        assertThat(detail.get("firstInbound").asText()).isEqualTo("2026-01-01");
        assertThat(detail.get("lastInbound").asText()).isEqualTo("2026-12-31");
        assertThat(detail.get("monthsWithInbound").asInt()).as("jan, fev, mar, abr, mai, set, out, dez").isEqualTo(8);
        assertThat(detail.get("monthsEvaluated").asInt()).isEqualTo(11);
        assertThat(detail.get("coverage").asText()).isEqualTo("PARTIAL");
        JsonNode months = detail.get("purchasesByMonth");
        assertThat(months).hasSize(12);
        assertThat(months.get(0).get("month").asText()).isEqualTo("2026-01");
        assertThat(months.get(0).get("amount").decimalValue()).isEqualByComparingTo("1000.10");
        assertThat(months.get(3).get("amount").decimalValue()).as("só o 3102 de abril").isEqualByComparingTo("300.00");
        assertThat(months.get(4).get("amount").decimalValue()).as("5202 é devolução").isEqualByComparingTo("0");
        assertThat(months.get(9).get("invoices").asInt()).as("item sem valor conta a nota").isEqualTo(1);
        assertThat(months.get(11).get("amount").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(detailRaw.raw()).contains("{\"month\":\"2026-06\",\"amount\":0.00,\"invoices\":0}");
        // dinheiro com 2 casas no JSON cru (o readTree lê como double)
        assertThat(detailRaw.raw()).contains("\"purchasesByPisCst\":[{\"cst\":\"01\",\"amount\":2000.21,\"items\":2},"
                + "{\"cst\":\"50\",\"amount\":1000.10,\"items\":1},{\"cst\":\"NO_CST\",\"amount\":400.00,\"items\":2},"
                + "{\"cst\":\"73\",\"amount\":300.00,\"items\":1}]");
        assertThat(detailRaw.raw()).contains("\"topNcm\":[{\"ncm\":\"22030000\",\"amount\":2000.21,\"items\":2},"
                + "{\"ncm\":\"10000001\",\"amount\":1000.10,\"items\":1},{\"ncm\":\"NO_NCM\",\"amount\":400.00,\"items\":2},"
                + "{\"ncm\":\"84719012\",\"amount\":300.00,\"items\":1}]");
        assertThat(detail.get("notice").asText()).startsWith("Levantamento de quem pode ter direito ao crédito presumido de CBS sobre o estoque de 31/12/2026");
        assertThat(detail.get("memory").toString()).contains("1101-1102").contains("Presumido");

        JsonNode december = get(SURVEY + "/" + fromDecember + "?year=2026", admin).body();
        assertThat(december.get("referenceRegimeSince").asText()).isEqualTo("2026-12-01");
        JsonNode meiDetail = get(SURVEY + "/" + mei + "?year=2026", admin).body();
        assertThat(meiDetail.get("referenceRegimeSource").asText()).isEqualTo("REGISTRATION");
        assertThat(meiDetail.get("coverage").asText()).isEqualTo("NO_INBOUND");
        assertThat(meiDetail.get("yearPurchases").decimalValue()).isEqualByComparingTo("0");
        assertThat(get(SURVEY + "/" + complete + "?year=2026", admin).body().get("coverage").asText()).isEqualTo("COMPLETE");

        // virada de mês e de ano na cobertura (CT-CE-10, CT-CE-11): 30/11 23:59 avalia 10 meses; 01/12 00:00 avalia 11
        clock.set(LocalDateTime.of(2026, 11, 30, 23, 59, 59));
        JsonNode nov = post(SURVEY + "/" + complete + "/refresh?year=2026", admin).body();
        assertThat(nov.get("monthsEvaluated").asInt()).isEqualTo(10);
        assertThat(nov.get("coverage").asText()).isEqualTo("COMPLETE");
        clock.set(LocalDateTime.of(2027, 1, 2, 9, 0));
        JsonNode jan = post(SURVEY + "/" + complete + "/refresh?year=2026", admin).body();
        assertThat(jan.get("monthsEvaluated").asInt()).isEqualTo(12);
        assertThat(jan.get("coverage").asText()).as("sem dezembro").isEqualTo("PARTIAL");
        assertThat(jan.get("calculatedAt").asText()).isEqualTo("2027-01-02T09:00:00-03:00");
    }

    // ================================================================== F5a: isolamento e entradas ruins

    /** CA-CE-05, CA-CE-15, CT-CE-22, CT-CE-23, CT-CE-30, Q-CE-2. */
    @Test
    void isolationByFirmAndPortfolio() throws Exception {
        String firm = it.firm("Escritorio Epsilon", "ACTIVE");
        String a = company(firm, "Carteira Um Ltda", "LUCRO_PRESUMIDO");
        String b = company(firm, "Carteira Dois Ltda", "LUCRO_REAL");
        String outside = company(firm, "Fora Da Carteira Ltda", "LUCRO_PRESUMIDO");
        String inactive = company(firm, "Inativa Ltda", "LUCRO_PRESUMIDO");
        jdbc.update("UPDATE companies SET active = 0 WHERE id = ?", inactive);
        item(note(outside, "INBOUND", null, LocalDateTime.of(2026, 3, 1, 9, 0), false), "1102", "5555.55", "01", "1");
        item(note(a, "INBOUND", null, LocalDateTime.of(2026, 3, 1, 9, 0), false), "1102", "100.00", "01", "1");
        user(firm, "admin", "ADMIN", null);
        String fiscalId = user(firm, "fiscal", "EMPLOYEE", "FISCAL");
        it.portfolio(fiscalId, a);
        it.portfolio(fiscalId, b);
        it.portfolio(fiscalId, inactive);
        String exId = user(firm, "fiscal.ex", "EMPLOYEE", "FISCAL");
        it.portfolio(exId, a);
        jdbc.update("UPDATE users_companies SET deleted = 1 WHERE user_id = ?", exId);
        String accountingId = user(firm, "contabil", "EMPLOYEE", "ACCOUNTING");
        it.portfolio(accountingId, a);
        String managementId = user(firm, "gestao", "EMPLOYEE", "MANAGEMENT");
        it.portfolio(managementId, a);
        user(firm, "financeiro", "EMPLOYEE", "FINANCIAL");
        String other = it.firm("Escritorio Zeta", "ACTIVE");
        String foreign = company(other, "Empresa De Outro Escritorio Ltda", "LUCRO_PRESUMIDO");
        user(other, "admin.z", "ADMIN", null);

        assertThat(scheduler.runOnce()).isPresent();
        // a empresa nova, depois da rodada: sem cálculo (fiscal a vê em notCalculated, como o admin)
        String later = company(firm, "Nova Na Carteira Ltda", "LUCRO_PRESUMIDO");
        it.portfolio(fiscalId, later);

        String admin = token("admin");
        String fiscal = token("fiscal");
        String ex = token("fiscal.ex");
        String contabil = token("contabil");
        String gestao = token("gestao");
        String financeiro = token("financeiro");
        String adminZ = token("admin.z");

        JsonNode adminList = get(SURVEY + "?year=2026", admin).body();
        assertThat(ids(adminList)).containsExactlyInAnyOrder(a, b, outside);
        assertThat(adminList.at("/summary/notCalculated").asInt()).isEqualTo(1);
        JsonNode fiscalList = get(SURVEY + "?year=2026", fiscal).body();
        assertThat(ids(fiscalList)).containsExactlyInAnyOrder(a, b);
        assertThat(fiscalList.at("/summary/companies").asInt()).isEqualTo(2);
        assertThat(fiscalList.at("/summary/notCalculated").asInt()).isEqualTo(1);
        assertThat(fiscalList.at("/summary/yearPurchasesTotal").decimalValue()).as("sem a de fora").isEqualByComparingTo("100.00");
        assertThat(fiscalList.at("/page/totalElements").asInt()).isEqualTo(2);
        assertThat(get(SURVEY + "?year=2026&search=Fora", fiscal).body().at("/page/content")).isEmpty();
        Resp fiscalCsv = get(SURVEY + "/export.csv?year=2026", fiscal);
        String csvText = new String(fiscalCsv.bytes(), StandardCharsets.UTF_8);
        assertThat(csvText).contains("Carteira Um Ltda").doesNotContain("Fora Da Carteira").doesNotContain("5555,55")
                .doesNotContain("Inativa");
        for (String path : List.of("/" + outside + "?year=2026", "/" + outside + "/history?year=2026",
                "/" + inactive + "?year=2026", "/" + foreign + "?year=2026", "/" + UUID.randomUUID() + "?year=2026")) {
            expectError(get(SURVEY + path, fiscal), 404, "error.Company.not.found");
        }
        expectError(post(SURVEY + "/" + outside + "/refresh?year=2026", fiscal), 404, "error.Company.not.found");
        expectError(put(SURVEY + "/" + outside + "/status", fiscal, statusBody("NOTIFIED", null, 0)), 404, "error.Company.not.found");

        // vínculo de carteira excluído: não vê nada
        assertThat(ids(get(SURVEY + "?year=2026", ex).body())).isEmpty();
        expectError(get(SURVEY + "/" + a + "?year=2026", ex), 404, "error.Company.not.found");

        // outro escritório: 404 em tudo, nada na lista nem no CSV
        for (String path : List.of("/" + a + "?year=2026", "/" + a + "/history?year=2026")) {
            expectError(get(SURVEY + path, adminZ), 404, "error.Company.not.found");
        }
        expectError(post(SURVEY + "/" + a + "/refresh?year=2026", adminZ), 404, "error.Company.not.found");
        expectError(put(SURVEY + "/" + a + "/status", adminZ, statusBody("NOTIFIED", null, 0)), 404, "error.Company.not.found");
        assertThat(ids(get(SURVEY + "?year=2026", adminZ).body())).containsExactly(foreign);
        assertThat(new String(get(SURVEY + "/export.csv?year=2026", adminZ).bytes(), StandardCharsets.UTF_8)).doesNotContain("Carteira Um");

        // perfis: ACCOUNTING e MANAGEMENT leem e exportam, não escrevem; FINANCIAL não lê
        for (String token : List.of(contabil, gestao)) {
            assertThat(get(SURVEY + "?year=2026", token).status()).isEqualTo(200);
            assertThat(get(SURVEY + "/export.csv?year=2026", token).status()).isEqualTo(200);
            assertThat(get(SURVEY + "/" + a + "?year=2026", token).status()).isEqualTo(200);
            assertThat(put(SURVEY + "/" + a + "/status", token, statusBody("NOTIFIED", null, 0)).status()).isEqualTo(403);
            assertThat(post(SURVEY + "/" + a + "/refresh?year=2026", token).status()).isEqualTo(403);
        }
        assertThat(get(SURVEY + "?year=2026", financeiro).status()).isEqualTo(403);
        assertThat(get(SURVEY + "/" + a + "?year=2026", financeiro).status()).isEqualTo(403);
        assertThat(count("SELECT version FROM stock_credit_surveys WHERE company_id = '" + a + "'")).isZero();

        // entradas ruins: LIKE, SQL, CRLF, ordem e paginação
        assertThat(get(SURVEY + "?year=2026&search=" + enc("%%%"), admin).body().at("/page/content")).as("% literal").isEmpty();
        assertThat(get(SURVEY + "?year=2026&search=" + enc("___"), admin).body().at("/page/content")).as("_ literal").isEmpty();
        Resp sql = get(SURVEY + "?year=2026&search=" + enc("' OR '1'='1"), admin);
        assertThat(sql.status()).isEqualTo(200);
        assertThat(sql.body().at("/page/content")).isEmpty();
        assertThat(get(SURVEY + "?year=2026&search=" + enc("Carteira\r\nUm"), admin).status()).isEqualTo(200);
        assertThat(ids(get(SURVEY + "?year=2026&search=" + enc("carteira um"), admin).body())).containsExactly(a);
        expectError(get(SURVEY + "?year=2026&sort=" + enc("companyName;DROP TABLE companies"), admin), 400, "error.reform.stock.filter.invalid");
        expectError(get(SURVEY + "?year=2026&sort=" + enc("c.name"), admin), 400, "error.reform.stock.filter.invalid");
        expectError(get(SURVEY + "?year=2026&size=0", admin), 400, "error.reform.stock.filter.invalid");
        expectError(get(SURVEY + "?year=2026&page=-1", admin), 400, "error.reform.stock.filter.invalid");
        expectError(get(SURVEY + "?year=2026&status=" + enc("NOTIFIED') OR 1=1 --"), admin), 400, "error.reform.stock.filter.invalid");
        expectError(get(SURVEY, admin), 400, "error.reform.stock.year.invalid");
        expectError(get(SURVEY + "?year=2027", admin), 400, "error.reform.stock.year.invalid");
        assertThat(get(SURVEY + "?year=abc", admin).status()).as("ano não numérico").isEqualTo(400);
        assertThat(get(SURVEY + "/nao-e-uuid?year=2026", admin).status()).as("id malformado").isIn(400, 404);
        assertThat(ids(get(SURVEY + "?year=2026&sort=yearPurchases,asc&classification=HYPOTHESIS_I,HYPOTHESIS_II_III", admin).body()))
                .containsExactly(b, a, outside);
        assertThat(ids(get(SURVEY + "?year=2026&classification=HYPOTHESIS_I&classification=HYPOTHESIS_II_III&sort=companyName,desc", admin).body()))
                .containsExactly(outside, a, b);
    }

    /** CT-CE-25: fórmula neutralizada em toda célula de texto; números intactos; BOM; ; e CRLF. */
    @Test
    void csvNeutralizesFormulasAndKeepsNumbers() throws Exception {
        String firm = it.firm("Escritorio Eta", "ACTIVE");
        user(firm, "admin", "ADMIN", null);
        String eq = company(firm, "=HYPERLINK(\"https://exemplo.test\";\"clique\")", "LUCRO_PRESUMIDO");
        String plus = company(firm, "+SOMA(1;2) Ltda", "LUCRO_PRESUMIDO");
        String minus = company(firm, "-2+3 Comercio Ltda", "LUCRO_PRESUMIDO");
        String at = company(firm, "@SOMA(A1) Ltda", "LUCRO_PRESUMIDO");
        String tab = company(firm, "\tTabulada Ltda", "LUCRO_PRESUMIDO");
        String cr = company(firm, "\rRetorno Ltda", "LUCRO_PRESUMIDO");
        String plain = company(firm, "Normal Ltda", "LUCRO_PRESUMIDO");
        item(note(plain, "INBOUND", null, LocalDateTime.of(2026, 3, 1, 9, 0), false), "1102", "160000.00", "01", "1");
        item(note(minus, "INBOUND", null, LocalDateTime.of(2026, 3, 1, 9, 0), false), "1102", "0.50", "01", "1");
        assertThat(scheduler.runOnce()).isPresent();
        String admin = token("admin");
        Resp csv = get(SURVEY + "/export.csv?year=2026", admin);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.headers().firstValue("Content-Disposition").orElse("")).isEqualTo("attachment; filename=\"credito-estoque-2026-20261210.csv\"");
        byte[] bytes = csv.bytes();
        assertThat(new byte[]{bytes[0], bytes[1], bytes[2]}).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).contains("\"'=HYPERLINK(\"\"https://exemplo.test\"\";\"\"clique\"\")\";")
                .contains("\"'+SOMA(1;2) Ltda\";")
                .contains("'-2+3 Comercio Ltda;")
                .contains("'@SOMA(A1) Ltda;")
                .contains("'\tTabulada Ltda;")
                .contains("Normal Ltda;");
        assertThat(text).contains("\"'\rRetorno Ltda\";");
        String normal = text.lines().filter(l -> l.startsWith("Normal Ltda;")).findFirst().orElseThrow();
        assertThat(normal).contains(";160000,00;1;1;").doesNotContain("'160000");
        String minusLine = text.lines().filter(l -> l.startsWith("'-2+3")).findFirst().orElseThrow();
        assertThat(minusLine).contains(";0,50;");
        for (String line : text.split("\r\n")) {
            for (String cell : line.split(";")) {
                String c = cell.startsWith("\"") ? cell.substring(1) : cell;
                if (!c.isEmpty() && "=+-@".indexOf(c.charAt(0)) >= 0) {
                    assertThat(c).as("célula sem neutralizar: " + line).matches("[-+]?\\d+([.,]\\d+)?");
                }
            }
        }
        assertThat(text.split("\r\n")[text.split("\r\n").length - 1]).startsWith("Observação;\"Levantamento de quem pode ter direito");
        assertThat(List.of(eq, plus, at, tab, cr)).hasSize(5);
    }

    // ================================================================== F5a: concorrência, situação, cooldown

    /** CA-CE-07, CA-CE-10, CT-CE-17..21: cliques simultâneos; uma reserva, um evento. */
    @Test
    void refreshCooldownAndStatusUnderConcurrency() throws Exception {
        String firm = it.firm("Escritorio Teta", "ACTIVE");
        user(firm, "admin", "ADMIN", null);
        user(firm, "fiscal", "EMPLOYEE", "FISCAL");
        String c = company(firm, "Concorrencia Exemplo Ltda", "LUCRO_PRESUMIDO");
        item(note(c, "INBOUND", null, LocalDateTime.of(2026, 3, 1, 9, 0), false), "1102", "10.00", "01", "1");
        String admin = token("admin");
        it.portfolio(jdbc.queryForObject("SELECT id FROM users WHERE login = ?", String.class, it.login("fiscal")), c);
        String fiscal = token("fiscal");

        // antes de qualquer cálculo: detalhe 404 do levantamento; situação 404
        expectError(get(SURVEY + "/" + c + "?year=2026", admin), 404, "error.reform.stock.survey.not.found");
        expectError(put(SURVEY + "/" + c + "/status", admin, statusBody("NOTIFIED", null, 0)), 404, "error.reform.stock.survey.not.found");

        clock.set(LocalDateTime.of(2026, 12, 10, 10, 0));
        List<Resp> clicks = parallel(6, () -> post(SURVEY + "/" + c + "/refresh?year=2026", admin));
        assertThat(clicks).extracting(Resp::status).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);
        clicks.stream().filter(r -> r.status() == 409).forEach(r -> {
            assertThat(r.code()).isEqualTo("error.reform.stock.refresh.cooldown");
            assertThat(r.body().get("retryAt").asText()).isEqualTo("2026-12-10T10:10:00-03:00");
        });
        assertThat(count("SELECT COUNT(*) FROM stock_credit_surveys WHERE company_id = '" + c + "'")).isEqualTo(1);
        clock.set(LocalDateTime.of(2026, 12, 10, 10, 9, 59));
        expectError(post(SURVEY + "/" + c + "/refresh?year=2026", admin), 409, "error.reform.stock.refresh.cooldown");
        JsonNode detail = get(SURVEY + "/" + c + "?year=2026", admin).body();
        assertThat(detail.get("refreshAvailableAt").asText()).isEqualTo("2026-12-10T10:10:00-03:00");
        clock.set(LocalDateTime.of(2026, 12, 10, 10, 10, 0));
        assertThat(post(SURVEY + "/" + c + "/refresh?year=2026", admin).status()).as("10 min exatos liberam").isEqualTo(200);
        assertThat(get(SURVEY + "/" + c + "?year=2026", admin).body().get("refreshAvailableAt").asText()).isEqualTo("2026-12-10T10:20:00-03:00");

        // situação: dois usuários com a mesma version
        List<Resp> changes = parallel(5, () -> put(SURVEY + "/" + c + "/status", fiscal, statusBody("NOTIFIED", "Cliente avisado QA", 0)));
        assertThat(changes).extracting(Resp::status).containsOnly(200, 409).filteredOn(s -> s == 200).hasSize(1);
        changes.stream().filter(r -> r.status() == 409).forEach(r -> {
            assertThat(r.code()).isEqualTo("error.reform.stock.status.conflict");
            assertThat(r.body().get("currentVersion").asInt()).isEqualTo(1);
        });
        JsonNode history = get(SURVEY + "/" + c + "/history?year=2026", admin).body();
        assertThat(history).hasSize(1);
        assertThat(history.at("/0/from").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(history.at("/0/to").asText()).isEqualTo("NOTIFIED");
        assertThat(history.at("/0/changedBy").asText()).isEqualTo(it.login("fiscal"));
        assertThat(history.at("/0/changedAt").asText()).isEqualTo("2026-12-10T10:10:00-03:00");
        expectError(put(SURVEY + "/" + c + "/status", admin, statusBody("NOTIFIED", null, null)), 409, "error.reform.stock.status.conflict");
        expectError(put(SURVEY + "/" + c + "/status", admin, statusBody("AVISADA", null, 1)), 400, "error.reform.stock.status.invalid");
        expectError(put(SURVEY + "/" + c + "/status", admin, statusBody("NOTIFIED", "x".repeat(501), 1)), 400, "error.reform.stock.note.too.long");
        assertThat(put(SURVEY + "/" + c + "/status", admin, statusBody("DISCARDED", "  " + "y".repeat(500) + "  ", 1)).status())
                .as("500 após aparar").isEqualTo(200);
        Resp crlf = put(SURVEY + "/" + c + "/status", admin, statusBody("INVENTORY_RECEIVED", "linha1\r\nlinha2 ' OR 1=1 -- %", 2));
        assertThat(crlf.status()).isEqualTo(200);
        assertThat(crlf.body().get("note").asText()).isEqualTo("linha1\r\nlinha2 ' OR 1=1 -- %");
        assertThat(crlf.body().get("version").asInt()).isEqualTo(3);

        // a rotina recalcula sem tocar situação, observação e version (CA-CE-09)
        clock.set(LocalDateTime.of(2026, 12, 11, 6, 15));
        assertThat(scheduler.runOnce()).isPresent();
        JsonNode after = get(SURVEY + "/" + c + "?year=2026", admin).body();
        assertThat(after.get("status").asText()).isEqualTo("INVENTORY_RECEIVED");
        assertThat(after.get("version").asInt()).isEqualTo(3);
        assertThat(after.get("note").asText()).startsWith("linha1");
        assertThat(after.get("calculatedAt").asText()).isEqualTo("2026-12-11T06:15:00-03:00");
        secrets.add("Cliente avisado QA");
        secrets.add("linha2 ' OR");
    }

    /** Pool de conexões: "Atualizar agora" em 12 empresas ao mesmo tempo (pool padrão 10) não pode travar nem dar 500. */
    @Test
    void manySimultaneousRefreshesDoNotStarveThePool() throws Exception {
        String firm = it.firm("Escritorio Omicron", "ACTIVE");
        user(firm, "admin", "ADMIN", null);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(company(firm, "Pool Exemplo " + i + " Ltda", "LUCRO_PRESUMIDO"));
        }
        String admin = token("admin");
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        long started = System.currentTimeMillis();
        List<Resp> results = parallel(12, () -> post(SURVEY + "/" + ids.get(next.getAndIncrement()) + "/refresh?year=2026", admin));
        long elapsed = System.currentTimeMillis() - started;
        System.out.println("QA-POOL statuses=" + results.stream().map(Resp::status).toList() + " elapsedMs=" + elapsed);
        assertThat(results).extracting(Resp::status).containsOnly(200);
        assertThat(elapsed).isLessThan(10_000L);
    }

    // ================================================================== F5a: encerramento, chave, bloqueio, teste grátis

    /** CA-CE-06, CA-CE-11, CA-CE-12, CT-CE-24, CT-CE-27, CT-CE-31. */
    @Test
    void closingSwitchBlockedAndTrial() throws Exception {
        String firm = it.firm("Escritorio Iota", "ACTIVE");
        user(firm, "admin", "ADMIN", null);
        String c = company(firm, "Encerramento Exemplo Ltda", "LUCRO_PRESUMIDO");
        String blockedFirm = it.firm("Escritorio Kappa", "BLOCKED");
        user(blockedFirm, "admin.b", "ADMIN", null);
        String w = company(blockedFirm, "Bloqueada Exemplo Ltda", "LUCRO_PRESUMIDO");
        jdbc.update("INSERT INTO stock_credit_surveys (id, company_id, survey_year, classification, reference_regime, purchases_total,"
                        + " purchase_items, inbound_notes, months_with_inbound, months_evaluated, coverage, calculated_at, contact_status,"
                        + " version, deleted, created_by, created_date) VALUES (?, ?, 2026, 'HYPOTHESIS_I', 'LUCRO_PRESUMIDO', 10.00, 1, 1,"
                        + " 1, 10, 'PARTIAL', ?, 'NOT_EVALUATED', 0, 0, 'qa', ?)", UUID.randomUUID().toString(), w,
                Timestamp.valueOf(NOW.minusDays(9)), Timestamp.valueOf(NOW.minusDays(9)));
        String trialFirm = it.firm("Escritorio Lambda", null);
        trial(trialFirm);
        user(trialFirm, "admin.t", "ADMIN", null);
        String t = company(trialFirm, "Teste Gratis Exemplo Ltda", "LUCRO_REAL");

        assertThat(scheduler.runOnce()).isPresent();
        String admin = token("admin");
        String adminB = token("admin.b");
        String adminT = token("admin.t");

        // bloqueado: lê o último cálculo, não escreve; a rotina pulou
        JsonNode blockedList = get(SURVEY + "?year=2026", adminB).body();
        assertThat(row(blockedList, w).get("calculatedAt").asText()).isEqualTo("2026-12-01T06:15:00-03:00");
        assertThat(get(SURVEY + "/export.csv?year=2026", adminB).status()).isEqualTo(200);
        assertThat(get(SURVEY + "/" + w + "?year=2026", adminB).status()).isEqualTo(200);
        assertThat(get(SURVEY + "/" + w + "/history?year=2026", adminB).status()).isEqualTo(200);
        assertThat(get("/reform/features", adminB).status()).isEqualTo(200);
        expectError(post(SURVEY + "/" + w + "/refresh?year=2026", adminB), 403, "error.firm.access.blocked");
        expectError(put(SURVEY + "/" + w + "/status", adminB, statusBody("NOTIFIED", null, 0)), 403, "error.firm.access.blocked");
        assertThat(count("SELECT version FROM stock_credit_surveys WHERE company_id = '" + w + "'")).isZero();

        // teste grátis: calculado e editável
        assertThat(row(get(SURVEY + "?year=2026", adminT).body(), t).get("classification").asText()).isEqualTo("HYPOTHESIS_II_III");
        assertThat(put(SURVEY + "/" + t + "/status", adminT, statusBody("NOTIFIED", null, 0)).status()).isEqualTo(200);

        // encerramento em 01/07/2027 00:00 de Recife
        clock.set(LocalDateTime.of(2027, 6, 30, 23, 59, 59));
        assertThat(put(SURVEY + "/" + c + "/status", admin, statusBody("NOTIFIED", null, 0)).status()).isEqualTo(200);
        assertThat(post(SURVEY + "/" + c + "/refresh?year=2026", admin).status()).isEqualTo(200);
        assertThat(get("/reform/features", admin).body().at("/stockCredit/closed").asBoolean()).isFalse();
        clock.set(LocalDateTime.of(2027, 7, 1, 0, 0, 0));
        expectError(put(SURVEY + "/" + c + "/status", admin, statusBody("DISCARDED", null, 1)), 409, "error.reform.stock.survey.closed");
        expectError(post(SURVEY + "/" + c + "/refresh?year=2026", admin), 409, "error.reform.stock.survey.closed");
        assertThat(get(SURVEY + "?year=2026", admin).status()).isEqualTo(200);
        assertThat(get(SURVEY + "/export.csv?year=2026", admin).status()).isEqualTo(200);
        assertThat(get(SURVEY + "/" + c + "?year=2026", admin).body().get("closed").asBoolean()).isTrue();
        JsonNode features = get("/reform/features", admin).body();
        assertThat(features.at("/stockCredit/closed").asBoolean()).isTrue();
        assertThat(features.at("/stockCredit/closesOn").asText()).isEqualTo("2027-06-30");
        jdbc.update("DELETE FROM routine_runs WHERE routine = 'STOCK_SURVEY'");
        logs.list.clear();
        assertThat(scheduler.runOnce()).as("rotina não roda encerrada").isEmpty();
        assertThat(messages()).contains("stock.survey.run.skipped runDate=2027-07-01 reason=CLOSED");
        assertThat(count("SELECT COUNT(*) FROM routine_runs WHERE routine = 'STOCK_SURVEY'")).isZero();

        // chave desligada: 404 em todas as rotas, features ativo=false, rotina não roda
        clock.set(NOW);
        properties.setEnabled(false);
        assertThat(get("/reform/features", admin).body().at("/stockCredit/enabled").asBoolean()).isFalse();
        for (Resp resp : List.of(get(SURVEY + "?year=2026", admin), get(SURVEY + "/export.csv?year=2026", admin),
                get(SURVEY + "/" + c + "?year=2026", admin), get(SURVEY + "/" + c + "/history?year=2026", admin),
                post(SURVEY + "/" + c + "/refresh?year=2026", admin), put(SURVEY + "/" + c + "/status", admin, statusBody("NOTIFIED", null, 1)),
                get(SURVEY + "?year=2025", admin), get(SURVEY + "/" + UUID.randomUUID() + "?year=2026", admin))) {
            expectError(resp, 404, "error.reform.stock.disabled");
        }
        assertThat(get(SURVEY + "?year=2026", null).status()).as("sem token continua 401").isEqualTo(401);
        logs.list.clear();
        assertThat(scheduler.runOnce()).isEmpty();
        assertThat(messages()).contains("stock.survey.run.skipped runDate=2026-12-10 reason=DISABLED");
        properties.setEnabled(true);
    }

    // ================================================================== F5a: rotina em duas instâncias e retry 09:00

    /** CA-CE-08, CT-CE-13..16, CT-CE-28 e o retry das 09:00 (TAXIFY_STOCK_SURVEY_RETRY_CRON). */
    @Test
    void routineOnTwoInstancesAndTheNineOClockRetry() throws Exception {
        String firm = it.firm("Escritorio Mu", "ACTIVE");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 53; i++) {
            String c = company(firm, "Lote Exemplo " + i + " Ltda", i % 2 == 0 ? "LUCRO_PRESUMIDO" : "LUCRO_REAL");
            item(note(c, "INBOUND", null, LocalDateTime.of(2026, 1 + i % 11, 5, 9, 0), false), "1102", (i + 1) + ".25", "01", "1");
            ids.add(c);
        }
        StockSurveyScheduler other = new StockSurveyScheduler(properties, sourceRepository, calculator,
                new RoutineRunReservation(routineRunRepository, clock), firmAccessService, clock);

        // 06:15, as duas instâncias no mesmo instante
        List<Optional<StockSurveyScheduler.RunSummary>> runs = parallelRuns(scheduler::runOnce, other::runOnce);
        assertThat(runs).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM routine_runs WHERE routine = 'STOCK_SURVEY'")).isEqualTo(1);
        Map<String, Object> run = jdbc.queryForMap("SELECT run_date, finished_at, processed, failed FROM routine_runs WHERE routine = 'STOCK_SURVEY'");
        assertThat(run.get("run_date").toString()).isEqualTo("2026-12-10");
        assertThat(run.get("finished_at")).isNotNull();
        assertThat(run.get("failed")).isEqualTo(0);
        assertThat(messages()).contains("stock.survey.run.skipped runDate=2026-12-10 reason=ALREADY_RESERVED");
        assertThat(count("SELECT COUNT(*) FROM stock_credit_surveys WHERE company_id IN (" + in(ids) + ")")).isEqualTo(53);
        assertThat(count("SELECT COUNT(*) FROM (SELECT company_id FROM stock_credit_surveys GROUP BY company_id, survey_year HAVING COUNT(*) > 1) x")).isZero();
        String totals = jdbc.queryForObject("SELECT CONCAT(SUM(purchases_total), '/', SUM(purchase_items)) FROM stock_credit_surveys WHERE company_id IN (" + in(ids) + ")", String.class);
        assertThat(totals).isEqualTo(new BigDecimal("1444.25").toPlainString() + "/53");

        // 09:00 do mesmo dia: o dia já rodou, o retry não refaz
        clock.set(LocalDateTime.of(2026, 12, 10, 9, 0));
        logs.list.clear();
        scheduler.retryTick();
        other.retryTick();
        assertThat(messages()).filteredOn(m -> m.contains("reason=ALREADY_RESERVED")).hasSize(2);
        assertThat(count("SELECT COUNT(*) FROM stock_credit_surveys WHERE calculated_at > '2026-12-10 06:15:00' AND company_id IN (" + in(ids) + ")")).isZero();

        // dia seguinte, a das 06:15 não rodou (deploy): o retry das 09:00 roda, numa instância só
        clock.set(LocalDateTime.of(2026, 12, 11, 9, 0));
        logs.list.clear();
        parallelRuns(() -> {
            scheduler.retryTick();
            return Optional.empty();
        }, () -> {
            other.retryTick();
            return Optional.empty();
        });
        assertThat(messages()).filteredOn(m -> m.startsWith("stock.survey.run.start runDate=2026-12-11")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM routine_runs WHERE routine = 'STOCK_SURVEY' AND run_date = '2026-12-11'"
                + " AND finished_at IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT MIN(calculated_at) FROM stock_credit_surveys WHERE company_id IN (" + in(ids) + ")",
                Timestamp.class).toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 11, 9, 0));

        // 3º dia: rodada das 06:15 presa (instância morreu); às 08:15 (120 min exatos) ninguém assume; às 09:00 uma assume
        jdbc.update("INSERT INTO routine_runs (id, routine, run_date, instance_id, started_at, deleted, created_by, created_date)"
                + " VALUES (?, 'STOCK_SURVEY', '2026-12-12', 'morta', '2026-12-12 06:15:00', 0, 'qa', '2026-12-12 06:15:00')", UUID.randomUUID().toString());
        clock.set(LocalDateTime.of(2026, 12, 12, 8, 15));
        assertThat(scheduler.runOnce()).as("120 min exatos: ainda não venceu").isEmpty();
        clock.set(LocalDateTime.of(2026, 12, 12, 9, 0));
        logs.list.clear();
        parallelRuns(() -> {
            scheduler.retryTick();
            return Optional.empty();
        }, () -> {
            other.retryTick();
            return Optional.empty();
        });
        assertThat(messages()).filteredOn(m -> m.startsWith("routine.run.taken.over routine=STOCK_SURVEY runDate=2026-12-12")).hasSize(1);
        assertThat(messages()).filteredOn(m -> m.startsWith("stock.survey.run.start runDate=2026-12-12")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM routine_runs WHERE routine = 'STOCK_SURVEY' AND run_date = '2026-12-12'"
                + " AND finished_at IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT instance_id FROM routine_runs WHERE routine = 'STOCK_SURVEY' AND run_date = '2026-12-12'",
                String.class)).isNotEqualTo("morta");
        assertThat(count("SELECT COUNT(*) FROM stock_credit_surveys WHERE company_id IN (" + in(ids) + ")")).isEqualTo(53);
    }

    /** CE-RN-07: uma empresa com dado ruim (regime fora do enum no histórico) não derruba o lote nem a rodada. */
    @Test
    void aBrokenCompanyDoesNotStopTheBatch() throws Exception {
        String firm = it.firm("Escritorio Nu", "ACTIVE");
        user(firm, "admin", "ADMIN", null);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(company(firm, "Lote Robusto " + i + " Ltda", "LUCRO_PRESUMIDO"));
        }
        String broken = ids.get(2);
        regime(broken, "REGIME_INEXISTENTE", LocalDate.of(2026, 1, 1));
        properties.setBatchSize(50);
        Optional<StockSurveyScheduler.RunSummary> run = scheduler.runOnce();
        assertThat(run).isPresent();
        assertThat(count("SELECT COUNT(*) FROM stock_credit_surveys WHERE calculated_at IS NOT NULL AND company_id IN (" + in(ids) + ")"))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT failed FROM routine_runs WHERE routine = 'STOCK_SURVEY'", Integer.class)).isGreaterThanOrEqualTo(1);
        assertThat(messages()).anyMatch(m -> m.startsWith("stock.survey.company.failed companyId=" + broken));
        assertThat(messages()).anyMatch(m -> m.startsWith("stock.survey.run.end runDate=2026-12-10"));
        String admin = token("admin");
        assertThat(get(SURVEY + "?year=2026", admin).body().at("/summary/notCalculated").asInt()).isEqualTo(1);
        expectError(get(SURVEY + "/" + broken + "?year=2026", admin), 404, "error.reform.stock.survey.not.found");
        Resp refresh = post(SURVEY + "/" + broken + "/refresh?year=2026", admin);
        System.out.println("QA-BROKEN refresh status=" + refresh.status() + " body=" + refresh.raw());
        assertThat(refresh.status()).as("refresh da empresa quebrada não pode ser 500 cru").isNotEqualTo(200);
        assertThat(refresh.raw()).doesNotContain("REGIME_INEXISTENTE").doesNotContain("at br.com");
    }

    // ================================================================== scenario

    private String company(String firm, String name, String regime) {
        String cnpj = ReformItSupport.randomCnpj();
        String id = it.company(firm, name, cnpj, regime);
        secrets.add(cnpj);
        secrets.add(name.strip());
        companies.add(id);
        return id;
    }

    private String user(String firm, String login, String profile, String accessProfile) {
        String id = it.user(firm, login, profile, accessProfile);
        secrets.add(it.login(login) + "." + it.prefix);
        secrets.add(login + "." + it.prefix + "@exemplo.test");
        return id;
    }

    private void trial(String firm) {
        jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, trial_ends_on, migrated, source, deleted, created_by,"
                + " created_date) VALUES (?, ?, 'TRIAL', '2027-12-31', 0, 'MANUAL', 0, 'qa', ?)", UUID.randomUUID().toString(), firm,
                Timestamp.valueOf(NOW));
    }

    private String regime(String company, String regime, LocalDate validFrom) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO company_tax_regimes (id, deleted, valid_from, created_date, tax_regime, company_id, created_by)"
                        + " VALUES (?, 0, ?, ?, ?, ?, 'qa')", id, validFrom == null ? null : java.sql.Date.valueOf(validFrom),
                Timestamp.valueOf(NOW), regime, company);
        return id;
    }

    private String note(String company, String direction, String status, LocalDateTime issuedAt, boolean deleted) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO fiscal_xml_documents (id, deleted, created_date, created_by, document_type, source_system, company_id,"
                        + " xml_file_path, direction, status, issue_date) VALUES (?, ?, ?, 'qa', 'NFE', 'SIEG', ?, 'xml/ficticio-qa.xml', ?, ?, ?)",
                id, deleted, Timestamp.valueOf(NOW), company, direction, status, Timestamp.valueOf(issuedAt));
        return id;
    }

    private void item(String document, String cfop, String value, String cstPis, String ncm) {
        jdbc.update("INSERT INTO fiscal_xml_document_items (id, document_id, cfop, valor_total, cst_pis, ncm) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), document, cfop, value == null ? null : new BigDecimal(value), cstPis, ncm);
    }

    /** Uma nota de saída com um item com o grupo IBSCBS (sem base, para só a alíquota ser conferida). */
    private String ibsNote(String company, LocalDateTime issuedAt, String cst, String classTrib, String cbs, String ibsUf,
                           String ibsMun, String status) {
        String document = note(company, "OUTBOUND", status, issuedAt, false);
        jdbc.update("INSERT INTO fiscal_xml_document_items (id, document_id, numero_item, cfop, valor_total, ibscbs_cst, ibscbs_class_trib,"
                        + " cbs_aliquota, ibs_uf_aliquota, ibs_mun_aliquota) VALUES (?, ?, 1, '5102', 100.00, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), document, cst, classTrib, new BigDecimal(cbs), new BigDecimal(ibsUf), new BigDecimal(ibsMun));
        return document;
    }

    private List<String> rateFindings(String token, String company) throws Exception {
        Resp resp = get("/product-fiscal-rules/conferencia?companyId=" + company + "&dataEmissaoInicio=2026-01-01&dataEmissaoFim=2027-12-31", token);
        assertThat(resp.status()).as(resp.raw()).isEqualTo(200);
        List<String> found = new ArrayList<>();
        for (JsonNode finding : resp.body()) {
            if ("IBSCBS_RATE".equals(finding.get("codigo").asText())) {
                found.add(finding.get("documentId").asText() + "|" + finding.get("campo").asText() + "|"
                        + finding.get("valorEncontrado").asText() + "|" + finding.get("valorEsperado").asText() + "|"
                        + finding.get("severidade").asText() + "|" + finding.get("mensagem").asText());
            }
        }
        return found;
    }

    // ================================================================== HTTP

    private String token(String login) throws Exception {
        String auth = Base64.getEncoder().encodeToString((it.login(login) + ":" + ReformItSupport.PASSWORD).getBytes(StandardCharsets.UTF_8));
        Resp resp = send("POST", "/auth", null, "{\"auth\":\"" + auth + "\"}");
        assertThat(resp.status()).as("login " + login + " " + resp.raw()).isEqualTo(200);
        String token = resp.body().get("token").asText();
        secrets.add(token.substring(Math.max(0, token.length() - 20)));
        return token;
    }

    private Resp get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private Resp post(String path, String token) throws Exception {
        return send("POST", path, token, "");
    }

    private Resp put(String path, String token, String body) throws Exception {
        return send("PUT", path, token, body);
    }

    private Resp rawPut(String path, String token, String body) throws Exception {
        return send("PUT", path, token, body);
    }

    private Resp send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/taxify-service/api/v1" + path))
                .header("Content-Type", "application/json").header("Accept-Language", "pt-BR");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] bytes = response.body();
        String raw = new String(bytes, StandardCharsets.UTF_8);
        if (response.statusCode() >= 500) {
            System.out.println("QA-5XX " + method + " " + path + " -> " + response.statusCode() + " " + raw);
        }
        return new Resp(response.statusCode(), raw, response.headers(), bytes, json);
    }

    private String rateBody(String status, String cbs, String ibsUf, String ibsMun, String basis, String url, Integer version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cbsRate", cbs == null ? null : new BigDecimal(cbs));
        body.put("ibsStateRate", ibsUf == null ? null : new BigDecimal(ibsUf));
        body.put("ibsMunicipalRate", ibsMun == null ? null : new BigDecimal(ibsMun));
        body.put("status", status);
        body.put("legalBasis", basis);
        body.put("sourceUrl", url);
        body.put("version", version);
        return json.writeValueAsString(body);
    }

    private String statusBody(String status, String note, Integer version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("year", 2026);
        body.put("status", status);
        body.put("note", note);
        body.put("version", version);
        return json.writeValueAsString(body);
    }

    private void expectError(Resp resp, int status, String code) {
        assertThat(resp.status()).as(resp.raw()).isEqualTo(status);
        assertThat(resp.code()).as(resp.raw()).isEqualTo(code);
        assertThat(resp.body().has("message")).as("erro único com mensagem").isTrue();
        assertThat(resp.raw()).doesNotContain("Exception").doesNotContain("at br.com").doesNotContain("SQL");
    }

    private <T> List<T> parallel(int n, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @SafeVarargs
    private List<Optional<StockSurveyScheduler.RunSummary>> parallelRuns(Callable<Optional<StockSurveyScheduler.RunSummary>>... calls)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<StockSurveyScheduler.RunSummary>>> futures = new ArrayList<>();
            for (Callable<Optional<StockSurveyScheduler.RunSummary>> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<Optional<StockSurveyScheduler.RunSummary>> results = new ArrayList<>();
            for (Future<Optional<StockSurveyScheduler.RunSummary>> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static String in(List<String> ids) {
        return String.join(",", ids.stream().map(id -> "'" + id + "'").toList());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static List<Integer> warningYears(JsonNode body) {
        List<Integer> years = new ArrayList<>();
        body.get("warnings").forEach(w -> years.add(w.get("year").asInt()));
        return years;
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
        throw new AssertionError("empresa fora da lista: " + company);
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
