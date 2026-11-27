package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.service.firmaccess.FirmAccessScheduler;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.firmaccess.PlanLimitService;
import br.com.cshub.taxify.service.firmaccess.UnverifiedSignupPurge;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import db.migration.ExistingFirmsRelease;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * QA da fase 1 do Taxify SaaS (CT1-03 a CT1-14, CT1-16 a CT1-19, CT1-23/24, CT1-25 a CT1-28, CT1-30 a CT1-36, CT1-40,
 * CT1-43 a CT1-45): os endpoints reais contra o MySQL local, com o relógio de Recife movido pelo teste e o e-mail pela
 * implementação que só registra. Dados fictícios (CNPJ e CPF com dígito verificador válido, e-mails @example.com, IPs de
 * documentação) gerados a cada execução.
 *
 * <p><b>Só roda com {@code DB_HOST=localhost}</b> (ou 127.0.0.1), e cada teste confere a URL real da conexão. O
 * {@code @AfterEach} apaga tudo o que o teste criou e antes disso confere que nenhuma linha de log (INFO ou acima)
 * carrega e-mail, CPF, CNPJ, IP, login ou token dos cadastros do teste (CT1-40).
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        // the daily purge and trial notices never run on their own against the shared test database
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        "taxify.saas.signup-enabled=true",
        "taxify.saas.terms-version=QA-2026-11",
        "taxify.saas.terms-url=https://exemplo.test/termos",
        "taxify.saas.privacy-url=https://exemplo.test/privacidade",
        "taxify.saas.dpa-url=https://exemplo.test/operador",
        "taxify.platform.admin-logins=qa.csadmin.a,qa.csadmin.b",
        "taxify.platform.firm-id=" + FirmAccessQaIntegrationTest.PLATFORM_FIRM,
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false"
})
@AutoConfigureMockMvc
class FirmAccessQaIntegrationTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-00000000cf02";
    private static final String PLATFORM_LOGIN = "qa.csadmin.a";
    private static final String OTHER_RESERVED_LOGIN = "qa.csadmin.b";
    private static final String PASSWORD = "senha-de-teste-123";
    private static final String BASE = "/api/v1";
    private static final Random RANDOM = new Random();

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(LocalDateTime.of(2026, 11, 26, 10, 0));
        }

        @Bean
        @Primary
        RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }
    }

    static class RecordingMailSender implements MailSender {
        final List<MailMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(MailMessage message) {
            sent.add(message);
        }

        @Override
        public String from() {
            return "nao-responda@cshub.com.br";
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
    private RecordingMailSender mail;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UnverifiedSignupPurge purge;
    @Autowired
    private FirmAccessScheduler accessScheduler;
    @Autowired
    private PlanLimitService planLimitService;

    /** CNPJs dos escritórios que este teste cadastrou pelo POST /signup. */
    private final List<String> signupCnpjs = new CopyOnWriteArrayList<>();
    /** Escritórios criados direto no banco (ids). */
    private final Set<String> rawFirms = new LinkedHashSet<>();
    /** Tudo o que não pode aparecer no log: e-mail, CPF, CNPJ, IP, login e token dos cadastros do teste. */
    private final Set<String> secrets = new LinkedHashSet<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    /** Os escritórios de fora do teste que o teste mexeu e precisa devolver. */
    private final List<Runnable> restores = new ArrayList<>();
    /** Firms that the purge deletes: their trail events stay behind and the test cleans them. */
    private final List<String> orphanEventFirms = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + connection);
        clock.set(LocalDateTime.of(2026, 11, 26, 10, 0));
        mail.sent.clear();
        cleanUp();
        logs.list.clear();
        logs.start();
        rootLogger.addAppender(logs);
        // the platform admin of the test: in the platform firm, which has no firm_subscription row (exempt)
        rawFirm(PLATFORM_FIRM, "CS Hub (teste QA)", PLATFORM_LOGIN, null);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        try {
            assertNoPersonalDataInLogs();
        } finally {
            restores.forEach(Runnable::run);
            restores.clear();
            cleanUp();
        }
    }

    // ================================================================== CT1-03, CT1-08, CT1-09, CT1-10, CT1-13, CT1-14, CT1-23, CT1-24

    @Test
    void signupValidationAndAntiAbuseOverHttp() throws Exception {
        // CT1-23 / CA1-13: open, with the real version, the three links, 14 days and 24 hours
        JsonNode config = body(mvc.perform(get(BASE + "/signup/config")).andReturn(), 200);
        assertThat(config.get("available").asBoolean()).isTrue();
        assertThat(config.get("termsVersion").asText()).isEqualTo("QA-2026-11");
        assertThat(config.get("termsUrl").asText()).isEqualTo("https://exemplo.test/termos");
        assertThat(config.get("privacyUrl").asText()).isEqualTo("https://exemplo.test/privacidade");
        assertThat(config.get("dpaUrl").asText()).isEqualTo("https://exemplo.test/operador");
        assertThat(config.get("trialDays").asInt()).isEqualTo(14);
        assertThat(config.get("verificationHours").asInt()).isEqualTo(24);

        String ip = ip(1);
        // CT1-24 / CA1-14: without a version → 400; an old one → 409
        String cnpj = cnpj();
        assertThat(code(signup(cnpj, login(), cpf(), ip, null, "(81) 9 9999-0000", "fin@example.com", "dona@example.com"), 400))
                .isEqualTo("error.signup.terms.required");
        assertThat(code(signup(cnpj, login(), cpf(), ip, "QA-2020-01", "(81) 9 9999-0000", "fin@example.com", "dona@example.com"), 409))
                .isEqualTo("error.signup.terms.outdated");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounting_firms WHERE document_number = ?", Integer.class, cnpj)).isZero();

        // CT1-08: invalid check digit → 400
        assertThat(code(signup("11111111000111", login(), cpf(), ip, "QA-2026-11", "81999990000", "fin@example.com", "a@example.com"), 400))
                .isEqualTo("error.signup.firm.document.invalid");
        // CT1-09: "(81) 9 9999-0000" is stored as 81999990000; "9999-0000" → 400. CT1-10: billing e-mail = user e-mail → 201
        String firstLogin = login();
        String firstCpf = cpf();
        body(signup(cnpj, firstLogin, firstCpf, ip, "QA-2026-11", "(81) 9 9999-0000", "mesmo@example.com", "mesmo@example.com"), 201);
        assertThat(jdbc.queryForObject("SELECT contact_phone FROM accounting_firms WHERE document_number = ?", String.class, cnpj))
                .isEqualTo("81999990000");
        assertThat(code(signup(cnpj(), login(), cpf(), ip, "QA-2026-11", "9999-0000", "fin@example.com", "b@example.com"), 400))
                .isEqualTo("error.signup.phone.invalid");

        // CA1-14: the acceptance records the user, the version, the date and time (Recife) and the IP
        Map<String, Object> acceptance = jdbc.queryForMap("SELECT t.terms_version, t.accepted_at, t.ip, t.user_id, u.login"
                + " FROM terms_acceptances t JOIN users u ON u.id = t.user_id WHERE t.accounting_firm_id ="
                + " (SELECT id FROM accounting_firms WHERE document_number = ?)", cnpj);
        assertThat(acceptance.get("terms_version")).isEqualTo("QA-2026-11");
        assertThat(acceptance.get("ip")).isEqualTo(ip);
        assertThat(acceptance.get("login")).isEqualTo(firstLogin);
        assertThat(((java.time.LocalDateTime) acceptance.get("accepted_at"))).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));

        // CT1-08: the same CNPJ again → 409 with the message of the spec
        JsonNode duplicate = body(signup(cnpj, login(), cpf(), ip(2), "QA-2026-11", "81999990000", "fin@example.com", "c@example.com"), 409);
        assertThat(duplicate.get("code").asText()).isEqualTo("error.signup.firm.document.exists");
        assertThat(duplicate.get("message").asText()).isEqualTo("Este CNPJ já tem conta no Taxify. Fale com a CS Hub.");

        // CT1-08: the CNPJ of a CLIENT company of another firm does not stop the signup (201)
        String clientCnpj = cnpj();
        String otherFirm = UUID.randomUUID().toString();
        rawFirm(otherFirm, "Outro Escritório QA", "qa.outro." + digits(8), null);
        insertCompany(otherFirm, clientCnpj, true);
        body(signup(clientCnpj, login(), cpf(), ip(3), "QA-2026-11", "81999990000", "fin@example.com", "d@example.com"), 201);

        // CT1-13 / CA1-07: 3 signups from one IP on 26/11, the 4th → 429; on 27/11 it goes through
        String busyIp = ip(50);
        for (int i = 0; i < 3; i++) {
            body(signup(cnpj(), login(), cpf(), busyIp, "QA-2026-11", "81999990000", "fin@example.com", "e" + i + "@example.com"), 201);
        }
        assertThat(code(signup(cnpj(), login(), cpf(), busyIp, "QA-2026-11", "81999990000", "fin@example.com", "e4@example.com"), 429))
                .isEqualTo("error.signup.rate.limited");
        clock.set(LocalDateTime.of(2026, 11, 26, 23, 59));
        assertThat(code(signup(cnpj(), login(), cpf(), busyIp, "QA-2026-11", "81999990000", "fin@example.com", "e5@example.com"), 429))
                .isEqualTo("error.signup.rate.limited");
        clock.set(LocalDateTime.of(2026, 11, 27, 0, 1));
        body(signup(cnpj(), login(), cpf(), busyIp, "QA-2026-11", "81999990000", "fin@example.com", "e6@example.com"), 201);

        // CT1-14: X-Forwarded-For sent straight to the backend from an address outside the trusted proxies is ignored
        String forgedCnpj = cnpj();
        String forgedLogin = login();
        secrets.add(forgedLogin);
        MvcResult forged = mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.51.100.77")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.99");
                    return request;
                })
                .content(json.writeValueAsString(signupBody(forgedCnpj, forgedLogin, cpf(), "QA-2026-11", "81999990000",
                        "fin@example.com", "f@example.com")))).andReturn();
        signupCnpjs.add(forgedCnpj);
        body(forged, 201);
        assertThat(jdbc.queryForObject("SELECT ip FROM terms_acceptances WHERE accounting_firm_id ="
                + " (SELECT id FROM accounting_firms WHERE document_number = ?)", String.class, forgedCnpj)).isEqualTo("203.0.113.99");
        secrets.add("203.0.113.99");
        secrets.add("198.51.100.77");
    }

    /** CT1-03 / CA1-23: the link lasts 24 h from the issue, to the minute; a used or expired one answers 400 and changes nothing. */
    @Test
    void theVerificationLinkWindowOverHttp() throws Exception {
        String loginA = login();
        String loginB = login();
        String cnpjA = cnpj();
        String cnpjB = cnpj();
        body(signup(cnpjA, loginA, cpf(), ip(1), "QA-2026-11", "81999990000", "fin@example.com", loginA + "@example.com"), 201);
        String tokenA = tokenOf(mail.sent.getLast());
        body(signup(cnpjB, loginB, cpf(), ip(2), "QA-2026-11", "81999990000", "fin@example.com", loginB + "@example.com"), 201);
        String tokenB = tokenOf(mail.sent.getLast());
        assertThat(mail.sent).hasSize(2).allSatisfy(message -> assertThat(message.body()).contains("https://taxify.cshub.com.br/auth/verificar?token="));

        // A opened on 27/11 at 09:59 (23 h 59 min after the issue) → TRIAL; the day counts as day 1 → 10/12
        clock.set(LocalDateTime.of(2026, 11, 27, 9, 59));
        JsonNode verifiedA = body(verify(tokenA), 200);
        assertThat(verifiedA.get("status").asText()).isEqualTo("TRIAL");
        assertThat(verifiedA.get("trialEndsOn").asText()).isEqualTo("2026-12-10");
        // B opened at 10:01 (24 h 01 min) → 400 and it stays pending
        clock.set(LocalDateTime.of(2026, 11, 27, 10, 1));
        assertThat(code(verify(tokenB), 400)).isEqualTo("error.signup.token.invalid");
        assertThat(subscriptionStatus(cnpjB)).isEqualTo("PENDING_VERIFICATION");
        // A again → 400
        assertThat(code(verify(tokenA), 400)).isEqualTo("error.signup.token.invalid");
        // a token that never existed → 400
        assertThat(code(verify("token-que-nunca-existiu"), 400)).isEqualTo("error.signup.token.invalid");
        // B still cannot log in (403) and is told so
        assertThat(code(authenticate(loginB, PASSWORD), 403)).isEqualTo("error.signup.email.not.verified");
        // events of A: EMAIL_VERIFIED and TRIAL_STARTED
        assertThat(jdbc.queryForList("SELECT type FROM firm_subscription_events WHERE accounting_firm_id = (SELECT id FROM accounting_firms"
                + " WHERE document_number = ?) ORDER BY created_date, type", String.class, cnpjA))
                .contains("SIGNUP", "EMAIL_VERIFIED", "TRIAL_STARTED");
    }

    // ================================================================== CT1-04, CT1-43, CT1-44

    /** CT1-04 / CA1-23: 3 resends per hour per login, the window is the last 60 minutes; the old link stops working. */
    @Test
    void resendHourlyLimitOverHttp() throws Exception {
        String login = login();
        String cnpj = cnpj();
        body(signup(cnpj, login, cpf(), ip(1), "QA-2026-11", "81999990000", "fin@example.com", login + "@example.com"), 201);
        String firstToken = tokenOf(mail.sent.getLast());
        assertThat(mail.sent).hasSize(1);

        // an unknown login and an existing one both answer 202; only the existing gets an e-mail
        assertThat(resend("login.que.nao.existe", ip(9)).getResponse().getStatus()).isEqualTo(202);
        assertThat(mail.sent).hasSize(1);

        int logsBefore = limitedLogs().size();
        for (LocalDateTime at : List.of(LocalDateTime.of(2026, 11, 26, 11, 0), LocalDateTime.of(2026, 11, 26, 11, 10),
                LocalDateTime.of(2026, 11, 26, 11, 20))) {
            clock.set(at);
            assertThat(resend(login, ip(9)).getResponse().getStatus()).isEqualTo(202);
        }
        assertThat(mail.sent).hasSize(4);
        // the e-mail of the signup no longer works: the first resend invalidated it
        assertThat(code(verify(firstToken), 400)).isEqualTo("error.signup.token.invalid");

        // the 4th inside 60 minutes: 202, nothing sent, and the log line without login, e-mail or IP
        clock.set(LocalDateTime.of(2026, 11, 26, 11, 30));
        assertThat(resend(login, ip(9)).getResponse().getStatus()).isEqualTo(202);
        assertThat(mail.sent).hasSize(4);
        assertThat(limitedLogs()).hasSize(logsBefore + 1);
        assertThat(limitedLogs().getLast()).isEqualTo("signup.resend.limited");

        // 12:01: the 11:00 resend left the 60 minute window (11:01-12:01) → it goes out
        clock.set(LocalDateTime.of(2026, 11, 26, 12, 1));
        assertThat(resend(login, ip(9)).getResponse().getStatus()).isEqualTo(202);
        assertThat(mail.sent).hasSize(5);
        // and the last link works
        assertThat(body(verify(tokenOf(mail.sent.getLast())), 200).get("status").asText()).isEqualTo("TRIAL");
    }

    /** CT1-43 / CA1-26: 5 resends a day per login (even paced out), the 6th is silent; the next civil day of Recife starts again. */
    @Test
    void resendDailyCapPerLoginOverHttp() throws Exception {
        clock.set(LocalDateTime.of(2026, 11, 26, 7, 50));
        String login = login();
        body(signup(cnpj(), login, cpf(), ip(1), "QA-2026-11", "81999990000", "fin@example.com", login + "@example.com"), 201);
        assertThat(mail.sent).hasSize(1);
        String resendIp = "203.0.113.10";
        secrets.add(resendIp);

        for (LocalDateTime at : List.of(LocalDateTime.of(2026, 11, 26, 8, 0), LocalDateTime.of(2026, 11, 26, 8, 10),
                LocalDateTime.of(2026, 11, 26, 8, 20), LocalDateTime.of(2026, 11, 26, 10, 0), LocalDateTime.of(2026, 11, 26, 10, 10))) {
            clock.set(at);
            resend(login, resendIp);
        }
        assertThat(mail.sent).as("the signup + 5 resends").hasSize(6);

        int logsBefore = capLogs().size();
        clock.set(LocalDateTime.of(2026, 11, 26, 15, 0));
        assertThat(resend(login, resendIp).getResponse().getStatus()).isEqualTo(202);
        assertThat(mail.sent).as("the 6th of the day is silent").hasSize(6);
        // some log line announces the cap and it carries no personal data (the global check also guards this at the end)
        assertThat(capLogs()).hasSize(logsBefore + 1);
        assertThat(capLogs().getLast()).doesNotContain(login).doesNotContain(resendIp).doesNotContain("@");

        // 23:59 still the same civil day; 00:05 of 27/11 goes out
        clock.set(LocalDateTime.of(2026, 11, 26, 23, 59));
        resend(login, resendIp);
        assertThat(mail.sent).hasSize(6);
        clock.set(LocalDateTime.of(2026, 11, 27, 0, 5));
        resend(login, resendIp);
        assertThat(mail.sent).hasSize(7);
    }

    /**
     * Spec F1-RN-03 / CA1-26 / CT1-43: above ANY of the limits the log records {@code signup.resend.limited}. The daily
     * cap logs {@code mail.resend.capped} instead (EmailVerificationService:180) - see BUG-QA-01 in the QA report.
     */
    @Test
    void dailyCapLogsTheNameOfTheSpec() throws Exception {
        clock.set(LocalDateTime.of(2026, 11, 26, 7, 50));
        String login = login();
        body(signup(cnpj(), login, cpf(), ip(1), "QA-2026-11", "81999990000", "fin@example.com", login + "@example.com"), 201);
        for (LocalDateTime at : List.of(LocalDateTime.of(2026, 11, 26, 8, 0), LocalDateTime.of(2026, 11, 26, 8, 10),
                LocalDateTime.of(2026, 11, 26, 8, 20), LocalDateTime.of(2026, 11, 26, 10, 0), LocalDateTime.of(2026, 11, 26, 10, 10))) {
            clock.set(at);
            resend(login, "203.0.113.10");
        }
        int before = limitedLogs().size();
        clock.set(LocalDateTime.of(2026, 11, 26, 15, 0));
        resend(login, "203.0.113.10");
        assertThat(limitedLogs()).hasSize(before + 1);
    }

    /** CT1-44 / CA1-26: 10 resends a day per IP (any logins); the 11th from that IP is silent, from another IP it goes out; unknown login = same 202. */
    @Test
    void resendDailyCapPerIpOverHttp() throws Exception {
        clock.set(LocalDateTime.of(2026, 11, 26, 7, 0));
        List<String> logins = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            String login = login();
            logins.add(login);
            body(signup(cnpj(), login, cpf(), "203.0.113." + (100 + i), "QA-2026-11", "81999990000", "fin@example.com", login + "@example.com"), 201);
        }
        String ipFull = "203.0.113.20";
        String ipOther = "203.0.113.21";
        secrets.add(ipFull);
        secrets.add(ipOther);
        int signupMails = mail.sent.size();
        assertThat(signupMails).isEqualTo(11);
        LocalDateTime at = LocalDateTime.of(2026, 11, 26, 8, 0);
        for (int i = 0; i < 10; i++) {
            clock.set(at.plusMinutes(i % 2 == 0 ? 0 : 10).plusHours(i / 2));
            assertThat(resend(logins.get(i), ipFull).getResponse().getStatus()).isEqualTo(202);
        }
        assertThat(mail.sent).as("10 resends from the same IP").hasSize(signupMails + 10);

        clock.set(LocalDateTime.of(2026, 11, 26, 14, 0));
        // 11th from the same IP, for another login: 202 and nothing
        assertThat(resend(logins.get(10), ipFull).getResponse().getStatus()).isEqualTo(202);
        assertThat(mail.sent).hasSize(signupMails + 10);
        // an unknown login from the same IP: the same 202 and nothing
        MvcResult unknown = resend("login.inexistente." + digits(6), ipFull);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(202);
        assertThat(unknown.getResponse().getContentAsString()).isEmpty();
        assertThat(mail.sent).hasSize(signupMails + 10);
        // the same request from another IP goes out
        assertThat(resend(logins.get(10), ipOther).getResponse().getStatus()).isEqualTo(202);
        assertThat(mail.sent).hasSize(signupMails + 11);
        // the answer of a capped, an unknown and a sent resend is identical (nothing reveals the account)
        MvcResult capped = resend(logins.get(1), ipFull);
        assertThat(capped.getResponse().getStatus()).isEqualTo(unknown.getResponse().getStatus());
        assertThat(capped.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());

        // LGPD: the IP kept for the cap is erased when the e-mail is verified
        String loginOne = logins.getFirst();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verifications WHERE requested_ip IS NOT NULL AND user_id ="
                + " (SELECT id FROM users WHERE login = ?)", Integer.class, loginOne)).isPositive();
        body(verify(tokenOf(lastMailTo(loginOne + "@example.com"))), 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verifications WHERE requested_ip IS NOT NULL AND user_id ="
                + " (SELECT id FROM users WHERE login = ?)", Integer.class, loginOne)).isZero();
    }

    // ================================================================== CT1-05

    /** CT1-05 / CA1-24: the purge against the real tables - 09:59 keeps, 10:01 deletes everything and frees CNPJ, login and CPF. */
    @Test
    void thePurgeOfAnUnverifiedSignupOverTheRealTables() throws Exception {
        Integer foreignPending = jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscriptions WHERE status = 'PENDING_VERIFICATION'", Integer.class);
        Assumptions.assumeTrue(foreignPending != null && foreignPending == 0,
                "the local database has other pending signups - the purge would delete them too");
        String cnpj = cnpj();
        String login = login();
        String cpf = cpf();
        body(signup(cnpj, login, cpf, ip(1), "QA-2026-11", "81999990000", "fin@example.com", login + "@example.com"), 201);
        String firmId = firmIdOf(cnpj);
        orphanEventFirms.add(firmId); // the purge deletes the firm but keeps its SIGNUP and UNVERIFIED_PURGED events: the test removes them
        // a resend on 01/12 does not restart the count
        clock.set(LocalDateTime.of(2026, 12, 1, 10, 0));
        resend(login, ip(1));
        // a verified firm of the same age is not touched
        String verifiedLogin = login();
        String verifiedCnpj = cnpj();
        clock.set(LocalDateTime.of(2026, 11, 26, 10, 0));
        body(signup(verifiedCnpj, verifiedLogin, cpf(), ip(2), "QA-2026-11", "81999990000", "fin@example.com", verifiedLogin + "@example.com"), 201);
        body(verify(tokenOf(mail.sent.getLast())), 200);

        clock.set(LocalDateTime.of(2026, 12, 3, 9, 59));
        assertThat(purge.purgeExpired()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounting_firms WHERE id = ?", Integer.class, firmId)).isOne();

        clock.set(LocalDateTime.of(2026, 12, 3, 10, 1));
        assertThat(purge.purgeExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounting_firms WHERE id = ?", Integer.class, firmId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE login = ?", Integer.class, login)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE number = ? AND type = 'CPF'", Integer.class, cpf)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM terms_acceptances WHERE accounting_firm_id = ?", Integer.class, firmId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscriptions WHERE accounting_firm_id = ?", Integer.class, firmId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounting_firms WHERE document_number = ?", Integer.class, verifiedCnpj)).isOne();
        // "nothing beyond": the verified firm keeps its user, document, acceptance, subscription (TRIAL) and events
        String keptFirm = firmIdOf(verifiedCnpj);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE login = ?", Integer.class, verifiedLogin)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM terms_acceptances WHERE accounting_firm_id = ?", Integer.class, keptFirm)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM firm_subscriptions WHERE accounting_firm_id = ?", String.class, keptFirm)).isEqualTo("TRIAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents d JOIN users u ON u.document_id = d.id WHERE u.login = ?", Integer.class, verifiedLogin)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscription_events WHERE accounting_firm_id = ? AND type IN ('SIGNUP','EMAIL_VERIFIED','TRIAL_STARTED')",
                Integer.class, keptFirm)).isEqualTo(3);
        // the purge touched only the one pending firm: exactly one UNVERIFIED_PURGED event exists for the whole run
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscription_events WHERE type = 'UNVERIFIED_PURGED' AND accounting_firm_id = ?", Integer.class, keptFirm)).isZero();

        // the purge event carries no personal data
        List<Map<String, Object>> purged = jdbc.queryForList("SELECT before_json, after_json, reason, actor FROM firm_subscription_events"
                + " WHERE accounting_firm_id = ? AND type = 'UNVERIFIED_PURGED'", firmId);
        assertThat(purged).hasSize(1);
        assertThat(purged.getFirst().get("before_json")).isNull();
        assertThat(purged.getFirst().get("after_json")).isNull();
        assertThat(purged.getFirst().get("actor")).isEqualTo("system");
        // and nothing left in the trail of that firm has the CNPJ, login, CPF, e-mail or phone
        String trail = jdbc.queryForList("SELECT CONCAT_WS('|', before_json, after_json, reason, actor) FROM firm_subscription_events"
                + " WHERE accounting_firm_id = ?", String.class, firmId).toString();
        assertThat(trail).doesNotContain(cnpj).doesNotContain(login).doesNotContain(cpf).doesNotContain("@").doesNotContain("81999990000");

        // the same CNPJ, login and CPF can sign up again
        clock.set(LocalDateTime.of(2026, 12, 3, 10, 2));
        body(signup(cnpj, login, cpf, ip(3), "QA-2026-11", "81999990000", "fin@example.com", login + "@example.com"), 201);
    }

    // ================================================================== CT1-15 to CT1-19, CT1-45

    /** CT1-15 to CT1-19 and CT1-45: the same read-only effect for expired trial, suspension and expired release; reads stay open, writes answer 403. */
    @Test
    void readOnlyOverHttpForEveryReason() throws Exception {
        String login = login();
        String cnpj = cnpj();
        body(signup(cnpj, login, cpf(), ip(1), "QA-2026-11", "81999990000", "fin@example.com", login + "@example.com"), 201);
        body(verify(tokenOf(mail.sent.getLast())), 200);
        String firmId = firmIdOf(cnpj);
        String owner = bearer(authenticate(login, PASSWORD));
        String companyId = id(createCompany(owner, cnpj(), true), 200);
        String platform = bearer(authenticate(PLATFORM_LOGIN, PASSWORD));
        String colleagueId = userId(login);

        // CT1-15: 09/12 23:59 - the last day, the write goes through
        clock.set(LocalDateTime.of(2026, 12, 9, 23, 59));
        body(createCompany(owner, cnpj(), true), 200);

        // CT1-17 / CA1-08: 10/12 00:05 - the writes answer 403 with the body of the spec
        clock.set(LocalDateTime.of(2026, 12, 10, 0, 5));
        assertAllWritesBlocked(owner, companyId, colleagueId, "TRIAL_EXPIRED", "2026-12-10");
        assertReadsOpen(owner, companyId);

        // CT1-19 / CA1-11: opened on 20/12, 10 days stopped and the 90 days warning of the SEFAZ
        clock.set(LocalDateTime.of(2026, 12, 20, 10, 0));
        JsonNode banner = body(mvc.perform(auth(get(BASE + "/firm-access"), owner)).andReturn(), 200);
        assertThat(banner.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(banner.get("readOnly").asBoolean()).isTrue();
        assertThat(banner.get("reason").asText()).isEqualTo("TRIAL_EXPIRED");
        assertThat(banner.get("blockedSince").asText()).isEqualTo("2026-12-10");
        assertThat(banner.get("capturesStoppedDays").asInt()).isEqualTo(10);
        assertThat(banner.get("sefazRetentionDays").asInt()).isEqualTo(90);

        // CT1-18: manual suspension → MANUAL
        body(platformAction(platform, firmId, "suspend", Map.of("reason", "QA: suspensão de teste")), 200);
        assertAllWritesBlocked(owner, companyId, colleagueId, "MANUAL", null);
        assertReadsOpen(owner, companyId);

        // CT1-45 / CA1-27: release until 31/12; on 01/01/2027 00:05 the write answers ACCESS_EXPIRED
        clock.set(LocalDateTime.of(2026, 12, 21, 10, 0));
        body(platformAction(platform, firmId, "release", Map.of("accessUntil", "2026-12-31", "reason", "QA")), 200);
        body(createCompany(owner, cnpj(), true), 200);
        clock.set(LocalDateTime.of(2026, 12, 31, 23, 59));
        body(createCompany(owner, cnpj(), true), 200);
        clock.set(LocalDateTime.of(2027, 1, 1, 0, 5));
        assertAllWritesBlocked(owner, companyId, colleagueId, "ACCESS_EXPIRED", "2027-01-01");
        assertReadsOpen(owner, companyId);
        // the access scheduler records BLOCKED with the reason (only when the local database has no other firm it could touch)
        Integer others = jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscriptions WHERE status IN ('TRIAL','ACTIVE')"
                + " AND accounting_firm_id <> ? AND ((trial_ends_on IS NOT NULL AND trial_ends_on < '2027-01-02')"
                + " OR (access_until IS NOT NULL AND access_until < '2027-01-02'))", Integer.class, firmId);
        if (others != null && others == 0) {
            assertThat(accessScheduler.recordExpired()).isEqualTo(1);
            Map<String, Object> row = jdbc.queryForMap("SELECT status, blocked_reason, blocked_since FROM firm_subscriptions WHERE accounting_firm_id = ?", firmId);
            assertThat(row.get("status")).isEqualTo("BLOCKED");
            assertThat(row.get("blocked_reason")).isEqualTo("ACCESS_EXPIRED");
            assertThat(row.get("blocked_since").toString()).isEqualTo("2027-01-01");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscription_events WHERE accounting_firm_id = ? AND type = 'BLOCKED'",
                    Integer.class, firmId)).isOne();
        }
        // release without a date → the next write goes through
        body(platformAction(platform, firmId, "release", Map.of("reason", "QA: sem data")), 200);
        body(createCompany(owner, cnpj(), true), 200);
    }

    // ================================================================== CT1-25 to CT1-28, CT1-30, CT1-42

    @Test
    void trialLimitsOverHttp() throws Exception {
        String login = login();
        String cnpj = cnpj();
        body(signup(cnpj, login, cpf(), ip(1), "QA-2026-11", "81999990000", "fin@example.com", login + "@example.com"), 201);
        body(verify(tokenOf(mail.sent.getLast())), 200);
        String firmId = firmIdOf(cnpj);
        String owner = bearer(authenticate(login, PASSWORD));
        String platform = bearer(authenticate(PLATFORM_LOGIN, PASSWORD));

        // CT1-42 / CA1-25: a matriz /0001 plus 3 others = 4 in use; the filial /0002 goes in (5), /0003 → 409
        String root = "9" + digits(7);
        String first = id(createCompany(owner, cnpj(), true), 200);
        id(createCompany(owner, cnpj(), true), 200);
        id(createCompany(owner, cnpj(), true), 200);
        id(createCompany(owner, withCheckDigits(root + "0001"), true), 200);
        id(createCompany(owner, withCheckDigits(root + "0002"), true), 200);
        int documentsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class);
        JsonNode refused = body(createCompany(owner, withCheckDigits(root + "0003"), true), 409);
        assertThat(refused.get("code").asText()).isEqualTo("error.plan.limit.companies");
        assertThat(refused.get("limit").asInt()).isEqualTo(5);
        assertThat(refused.get("inUse").asInt()).isEqualTo(5);
        assertThat(refused.get("planName").asText()).isEqualTo("Teste grátis");
        // CT1-25: nothing was created
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class)).isEqualTo(documentsBefore);
        assertThat(inUse(firmId)).isEqualTo(5);

        // CT1-27: at 5/5 the reactivation of an inactive company → 409 and it stays inactive
        jdbc.update("UPDATE companies SET active = 0 WHERE id = ?", first);
        assertThat(inUse(firmId)).isEqualTo(4);
        // CT1-26: the inactive one does not count - a new company goes in (5 in use + 1 inactive); the console says so
        id(createCompany(owner, cnpj(), true), 200);
        assertThat(inUse(firmId)).isEqualTo(5);
        JsonNode row = firmRow(platform, cnpj);
        assertThat(row.get("companiesInUse").asLong()).isEqualTo(5);
        assertThat(row.get("companiesInactive").asLong()).isEqualTo(1);
        JsonNode reactivation = body(updateCompanyActive(owner, first, true), 409);
        assertThat(reactivation.get("code").asText()).isEqualTo("error.plan.limit.companies");
        assertThat(jdbc.queryForObject("SELECT active FROM companies WHERE id = ?", Integer.class, first)).isZero();
        // editing an active company is never blocked by the limit
        String active = jdbc.queryForObject("SELECT id FROM companies WHERE accounting_firm_id = ? AND active = 1 LIMIT 1", String.class, firmId);
        body(updateCompanyActive(owner, active, true), 200);
        // a deletion is never blocked and the room comes back at once: the reactivation goes through
        assertThat(mvc.perform(auth(MockMvcRequestBuilders.delete(BASE + "/companies/" + active), owner)).andReturn().getResponse().getStatus()).isEqualTo(204);
        body(updateCompanyActive(owner, first, true), 200);
        assertThat(inUse(firmId)).isEqualTo(5);

        // CT1-28: the ADMIN counts - one colleague goes in, the next → 409; after deleting him, 201 again
        id(createUser(owner), 200);
        JsonNode usersLimit = body(createUser(owner), 409);
        assertThat(usersLimit.get("code").asText()).isEqualTo("error.plan.limit.users");
        assertThat(usersLimit.get("limit").asInt()).isEqualTo(2);
        assertThat(usersLimit.get("inUse").asInt()).isEqualTo(2);
        String colleague = jdbc.queryForObject("SELECT id FROM users WHERE accounting_firm_id = ? AND profile <> 'ADMIN' AND deleted = 0", String.class, firmId);
        assertThat(mvc.perform(auth(MockMvcRequestBuilders.delete(BASE + "/users/" + colleague), owner)).andReturn().getResponse().getStatus()).isEqualTo(204);
        id(createUser(owner), 200);

        // CT1-30: the creation by POST /companies racing the Acessórias path (withCompanySlot): one room, one winner
        assertThat(mvc.perform(auth(MockMvcRequestBuilders.delete(BASE + "/companies/" + first), owner)).andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(inUse(firmId)).isEqualTo(4);
        java.util.UUID firm = java.util.UUID.fromString(firmId);
        CompletableFuture<Boolean> api = CompletableFuture.supplyAsync(() -> statusOf(() -> createCompany(owner, cnpj(), true)) == 200);
        CompletableFuture<Boolean> importer = CompletableFuture.supplyAsync(() -> planLimitService.withCompanySlot(firm,
                () -> insertCompany(firmId, cnpj(), true)).isPresent());
        assertThat(List.of(api.get(), importer.get())).containsExactlyInAnyOrder(true, false);
        assertThat(inUse(firmId)).isEqualTo(5);
    }

    // ================================================================== CT1-31, CT1-32, CA1-18

    /** CT1-31 / CT1-32 / CA1-18: the released firm with 300 companies and the exempt one with 500 are never limited or blocked. */
    @Test
    void releasedAndExemptFirmsAreNeverLimitedOverHttp() throws Exception {
        // released (ACTIVE, migrated, no end) with 300 companies and 3 users
        String releasedFirm = UUID.randomUUID().toString();
        String releasedLogin = "qa.rel." + digits(8);
        rawFirm(releasedFirm, "Escritório Liberado QA", releasedLogin, "ACTIVE");
        jdbc.update("UPDATE firm_subscriptions SET migrated = 1, notes = 'liberado na migração' WHERE accounting_firm_id = ?", releasedFirm);
        bulkCompanies(releasedFirm, 300);
        String released = bearer(authenticate(releasedLogin, PASSWORD));
        id(createCompany(released, cnpj(), true), 200);
        id(createUser(released), 200);
        id(createUser(released), 200);
        JsonNode view = body(mvc.perform(auth(get(BASE + "/firm-access"), released)).andReturn(), 200);
        assertThat(view.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.get("readOnly").asBoolean()).isFalse();
        assertThat(view.get("limits").isNull()).isTrue();

        // exempt: the platform firm, no firm_subscription row, 500 companies; even years later nothing is blocked
        bulkCompanies(PLATFORM_FIRM, 500);
        String platform = bearer(authenticate(PLATFORM_LOGIN, PASSWORD));
        id(createCompany(platform, cnpj(), true), 200);
        id(createUser(platform), 200);
        id(createUser(platform), 200);
        clock.set(LocalDateTime.of(2028, 6, 1, 12, 0));
        id(createCompany(platform, cnpj(), true), 200);
        JsonNode exempt = body(mvc.perform(auth(get(BASE + "/firm-access"), platform)).andReturn(), 200);
        assertThat(exempt.get("exempt").asBoolean()).isTrue();
        assertThat(exempt.get("readOnly").asBoolean()).isFalse();
        assertThat(exempt.get("platformAdmin").asBoolean()).isTrue();
        assertThat(exempt.get("limits").isNull()).isTrue();
    }

    // ================================================================== CT1-33 to CT1-36

    /** CT1-33 / CA1-19 / CT1-34 / CT1-36: the migration against the real tables, the console filters and the review actions. */
    @Test
    void migrationConsoleAndReviewOverTheRealTables() throws Exception {
        List<String> mine = new ArrayList<>();
        List<String> logins = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String firmId = UUID.randomUUID().toString();
            String login = "qa.mig." + digits(8);
            rawFirm(firmId, "QA Migrado " + i, login, null);
            mine.add(firmId);
            logins.add(login);
        }
        // firms of the local database that are not mine and have no row: the migration would release them too - put them back after
        List<String> foreign = jdbc.queryForList("SELECT f.id FROM accounting_firms f WHERE f.deleted = 0 AND NOT EXISTS"
                + " (SELECT 1 FROM firm_subscriptions s WHERE s.accounting_firm_id = f.id)", String.class);
        foreign.removeAll(mine);
        foreign.remove(PLATFORM_FIRM);
        restores.add(() -> foreign.forEach(firm -> {
            jdbc.update("DELETE FROM firm_subscription_events WHERE accounting_firm_id = ? AND type = 'RELEASED' AND actor = 'system'", firm);
            jdbc.update("DELETE FROM firm_subscriptions WHERE accounting_firm_id = ? AND migrated = 1 AND notes = 'liberado na migração'", firm);
        }));

        LocalDateTime now = LocalDateTime.of(2026, 11, 26, 10, 0);
        ExistingFirmsRelease release = new ExistingFirmsRelease(jdbc);
        assertThat(release.run(now)).isGreaterThanOrEqualTo(3);
        assertThat(release.run(now)).as("second run").isZero();
        for (String firmId : mine) {
            Map<String, Object> row = jdbc.queryForMap("SELECT status, access_until, migrated, reviewed_at, notes, source, deleted"
                    + " FROM firm_subscriptions WHERE accounting_firm_id = ?", firmId);
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            assertThat(row.get("access_until")).isNull();
            assertThat(row.get("reviewed_at")).isNull();
            assertThat(row.get("migrated")).isIn(true, 1);
            assertThat(row.get("notes")).isEqualTo("liberado na migração");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscription_events WHERE accounting_firm_id = ? AND type = 'RELEASED'"
                    + " AND actor = 'system'", Integer.class, firmId)).isOne();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscriptions WHERE accounting_firm_id IN (?, ?, ?)", Integer.class,
                mine.get(0), mine.get(1), mine.get(2))).isEqualTo(3);

        // right after the deploy: nothing blocked, no limit (CA1-19)
        String owner = bearer(authenticate(logins.getFirst(), PASSWORD));
        id(createCompany(owner, cnpj(), true), 200);
        id(createUser(owner), 200);
        id(createUser(owner), 200);
        // a client company and a colleague that the console must never show
        id(createCompanyNamed(owner, cnpj(), "Cliente Secreto QA Ltda"), 200);
        userCreateNamed(owner, "Colaborador Secreto QA");

        // CT1-36: console
        String platform = bearer(authenticate(PLATFORM_LOGIN, PASSWORD));
        JsonNode page = body(mvc.perform(auth(get(BASE + "/platform/firms").param("unreviewed", "true").param("q", "QA Migrado")
                .param("size", "200"), platform)).andReturn(), 200);
        assertThat(page.get("content")).hasSize(3);
        String raw = page.toString();
        assertThat(raw).doesNotContain("Cliente Secreto").doesNotContain("Colaborador Secreto");
        JsonNode first = null;
        for (JsonNode node : page.get("content")) {
            if (node.get("id").asText().equals(mine.getFirst())) {
                first = node;
            }
        }
        assertThat(first).isNotNull();
        assertThat(first.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(first.get("migrated").asBoolean()).isTrue();
        assertThat(first.get("reviewed").asBoolean()).isFalse();
        assertThat(first.get("usersInUse").asInt()).isEqualTo(4);
        assertThat(first.get("companiesInUse").asInt()).isEqualTo(2);
        assertThat(first.get("admins")).hasSize(1);
        assertThat(first.get("admins").get(0).get("email").asText()).isEqualTo(logins.getFirst() + "@example.com");
        // an ordinary ADMIN and an anonymous call do not get in
        assertThat(mvc.perform(auth(get(BASE + "/platform/firms"), owner)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(BASE + "/platform/firms")).andReturn().getResponse().getStatus()).isIn(401, 403);

        // CT1-34 / CA1-20: convert-to-trial without a date on 26/11 → TRIAL until 09/12, reviewed, event with the admin's login
        JsonNode converted = body(platformAction(platform, mine.get(0), "convert-to-trial", Map.of()), 200);
        assertThat(converted.get("status").asText()).isEqualTo("TRIAL");
        assertThat(converted.get("trialEndsOn").asText()).isEqualTo("2026-12-09");
        assertThat(converted.get("reviewed").asBoolean()).isTrue();
        JsonNode events = body(mvc.perform(auth(get(BASE + "/platform/firms/" + mine.get(0) + "/events"), platform)).andReturn(), 200);
        // (the clock is frozen at 10:00, so the RELEASED of the migration has the same second: look the event up by type)
        JsonNode convertedEvent = null;
        for (JsonNode event : events) {
            if ("CONVERTED_TO_TRIAL".equals(event.get("type").asText())) {
                convertedEvent = event;
            }
        }
        assertThat(convertedEvent).isNotNull();
        assertThat(convertedEvent.get("actor").asText()).isEqualTo(PLATFORM_LOGIN);
        // mark-reviewed keeps it released and leaves the "not reviewed" filter
        JsonNode reviewed = body(platformAction(platform, mine.get(1), "mark-reviewed", Map.of()), 200);
        assertThat(reviewed.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(reviewed.get("reviewed").asBoolean()).isTrue();
        JsonNode remaining = body(mvc.perform(auth(get(BASE + "/platform/firms").param("unreviewed", "true").param("q", "QA Migrado"), platform)).andReturn(), 200);
        assertThat(remaining.get("content")).hasSize(1);
        assertThat(remaining.get("content").get(0).get("id").asText()).isEqualTo(mine.get(2));
        // suspend without a reason → 400
        assertThat(code(platformAction(platform, mine.get(2), "suspend", Map.of()), 400)).isEqualTo("error.platform.reason.required");
        // an action by an ordinary ADMIN → 403
        assertThat(mvc.perform(auth(post(BASE + "/platform/firms/" + mine.get(2) + "/access/release"), owner)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    // ================================================================== CA1-06 / CT1-12 and unauthenticated access

    @Test
    void aReservedLoginInAnotherFirmIsNotPlatformAdminAndTheEndpointsNeedAToken() throws Exception {
        // CT1-12: qa.csadmin.b is in the list, but lives in another firm
        String otherFirm = UUID.randomUUID().toString();
        rawFirm(otherFirm, "Escritório F9 QA", OTHER_RESERVED_LOGIN, "ACTIVE");
        String other = bearer(authenticate(OTHER_RESERVED_LOGIN, PASSWORD));
        assertThat(body(mvc.perform(auth(get(BASE + "/firm-access"), other)).andReturn(), 200).get("platformAdmin").asBoolean()).isFalse();
        assertThat(mvc.perform(auth(get(BASE + "/platform/firms"), other)).andReturn().getResponse().getStatus()).isEqualTo(403);
        // the real one is
        String platform = bearer(authenticate(PLATFORM_LOGIN, PASSWORD));
        assertThat(body(mvc.perform(auth(get(BASE + "/firm-access"), platform)).andReturn(), 200).get("platformAdmin").asBoolean()).isTrue();
        // CT1-11: the reserved login is refused in the signup with the answer of "login in use"
        JsonNode refused = body(signup(cnpj(), OTHER_RESERVED_LOGIN, cpf(), ip(1), "QA-2026-11", "81999990000", "fin@example.com", "x@example.com"), 409);
        assertThat(refused.get("code").asText()).isEqualTo("error.user.login.already.exists");
        // and in upper case too
        assertThat(code(signup(cnpj(), OTHER_RESERVED_LOGIN.toUpperCase(), cpf(), ip(1), "QA-2026-11", "81999990000", "fin@example.com", "y@example.com"), 409))
                .isEqualTo("error.user.login.already.exists");
        // security: no token → 401/403 (never 200) on what is not public
        for (String path : List.of("/firm-access", "/platform/firms", "/companies", "/users", "/integrations/xmls/totais")) {
            int status = mvc.perform(get(BASE + path)).andReturn().getResponse().getStatus();
            assertThat(status).as("GET %s without a token", path).isIn(401, 403);
        }
        assertThat(mvc.perform(post(BASE + "/integrations/xmls").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn()
                .getResponse().getStatus()).isIn(401, 403);
        assertThat(mvc.perform(post(BASE + "/platform/firms/" + otherFirm + "/access/release").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn().getResponse().getStatus()).isIn(401, 403);
    }

    // ================================================================== helpers: assertions

    private void assertAllWritesBlocked(String bearer, String companyId, String userId, String reason, String blockedSince) throws Exception {
        List<MvcResult> writes = List.of(
                createCompany(bearer, cnpj(), true),
                mvc.perform(auth(MockMvcRequestBuilders.put(BASE + "/users/" + userId), bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{}")).andReturn(),
                mvc.perform(auth(post(BASE + "/integrations/xmls/" + UUID.randomUUID() + "/retificar"), bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(),
                mvc.perform(auth(post(BASE + "/companies/" + companyId + "/sefaz/capturar"), bearer)).andReturn(),
                mvc.perform(auth(post(BASE + "/companies/" + companyId + "/nfse-nacional/capturar"), bearer)).andReturn(),
                mvc.perform(auth(post(BASE + "/integrations/xmls/sincronizar"), bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{}")).andReturn(),
                mvc.perform(MockMvcRequestBuilders.multipart(BASE + "/companies/" + companyId + "/certificado")
                        .file(new MockMultipartFile("file", "a1.pfx", "application/x-pkcs12", new byte[]{1, 2, 3}))
                        .header("Authorization", "Bearer " + bearer)).andReturn(),
                mvc.perform(auth(post(BASE + "/auditoria/executar"), bearer)).andReturn(),
                mvc.perform(auth(post(BASE + "/fronteira/sincronizar"), bearer)).andReturn(),
                mvc.perform(auth(MockMvcRequestBuilders.delete(BASE + "/companies/" + companyId), bearer)).andReturn());
        for (MvcResult write : writes) {
            String uri = write.getRequest().getMethod() + " " + write.getRequest().getRequestURI();
            JsonNode blocked = json.readTree(write.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(write.getResponse().getStatus()).as(uri).isEqualTo(403);
            assertThat(blocked.get("code").asText()).as(uri).isEqualTo("error.firm.access.blocked");
            assertThat(blocked.get("status").asInt()).as(uri).isEqualTo(403);
            assertThat(blocked.get("firmStatus").asText()).as(uri).isIn("BLOCKED", "SUSPENDED", "CANCELED");
            assertThat(blocked.get("reason").asText()).as(uri).isEqualTo(reason);
            if (blockedSince != null) {
                assertThat(blocked.get("blockedSince").asText()).as(uri).isEqualTo(blockedSince);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE id = ? AND deleted = 0", Integer.class, companyId)).isOne();
    }

    /** CT1-16: the reads (GET and the POSTs that only read) are not refused by the block - none answers 403 error.firm.access.blocked, none 5xx. */
    private void assertReadsOpen(String bearer, String companyId) throws Exception {
        String filter = json.writeValueAsString(Map.of("companyId", companyId, "xmlType", 1));
        Map<String, MvcResult> reads = new LinkedHashMap<>();
        reads.put("POST /integrations/xmls", mvc.perform(auth(post(BASE + "/integrations/xmls"), bearer).contentType(MediaType.APPLICATION_JSON).content(filter)).andReturn());
        reads.put("POST /integrations/xmls/totais", mvc.perform(auth(post(BASE + "/integrations/xmls/totais"), bearer).contentType(MediaType.APPLICATION_JSON).content(filter)).andReturn());
        reads.put("POST /integrations/xmls/exportar", mvc.perform(auth(post(BASE + "/integrations/xmls/exportar"), bearer).contentType(MediaType.APPLICATION_JSON).content(filter)).andReturn());
        reads.put("POST /integrations/xmls/exportar/zip", mvc.perform(auth(post(BASE + "/integrations/xmls/exportar/zip"), bearer).contentType(MediaType.APPLICATION_JSON).content(filter)).andReturn());
        reads.put("POST /fronteira/memoria/buscar", mvc.perform(auth(post(BASE + "/fronteira/memoria/buscar").param("companyId", companyId).param("registro", "123"), bearer)).andReturn());
        reads.put("POST /fronteira/regras/testar", mvc.perform(auth(post(BASE + "/fronteira/regras/testar"), bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"formula\":\"1+1\",\"variaveis\":{}}")).andReturn());
        reads.put("POST /notas-servico/buscar", mvc.perform(auth(post(BASE + "/notas-servico/buscar"), bearer).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn());
        reads.put("GET /fronteira/relatorio.pdf", mvc.perform(auth(get(BASE + "/fronteira/relatorio.pdf").param("companyId", companyId), bearer)).andReturn());
        reads.put("GET /fronteira/relatorio.csv", mvc.perform(auth(get(BASE + "/fronteira/relatorio.csv").param("companyId", companyId), bearer)).andReturn());
        reads.put("GET /relatorios/mensal", mvc.perform(auth(get(BASE + "/relatorios/mensal").param("companyId", companyId), bearer)).andReturn());
        reads.put("GET /auditoria/laudo.pdf", mvc.perform(auth(get(BASE + "/auditoria/laudo.pdf").param("companyId", companyId), bearer)).andReturn());
        reads.put("GET /certidoes/carteira", mvc.perform(auth(get(BASE + "/certidoes/carteira"), bearer)).andReturn());
        reads.put("GET /companies", mvc.perform(auth(get(BASE + "/companies"), bearer)).andReturn());
        reads.put("GET /firm-access", mvc.perform(auth(get(BASE + "/firm-access"), bearer)).andReturn());
        reads.forEach((name, result) -> {
            String content = bodyOf(result);
            assertThat(content).as(name).doesNotContain("error.firm.access.blocked");
            assertThat(result.getResponse().getStatus()).as(name + " -> " + result.getResponse().getStatus()).isLessThan(500);
            readStatuses.put(name, result.getResponse().getStatus());
        });
        System.out.println("QA-READ-STATUSES " + readStatuses);
        // the five that the spec says must answer 200 with a valid request
        assertThat(readStatuses.get("POST /integrations/xmls")).isEqualTo(200);
        assertThat(readStatuses.get("POST /integrations/xmls/totais")).isEqualTo(200);
        assertThat(readStatuses.get("POST /fronteira/regras/testar")).isEqualTo(200);
        assertThat(readStatuses.get("GET /companies")).isEqualTo(200);
        assertThat(readStatuses.get("GET /firm-access")).isEqualTo(200);
    }

    /** What each read answered (for the QA report; a 4xx here is the request, not the block). */
    private final Map<String, Integer> readStatuses = new LinkedHashMap<>();

    private void assertNoPersonalDataInLogs() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : logs.list) {
            if (!event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.INFO)) {
                continue;
            }
            StringBuilder line = new StringBuilder(event.getFormattedMessage());
            IThrowableProxy throwable = event.getThrowableProxy();
            while (throwable != null) {
                line.append(' ').append(throwable.getMessage());
                throwable = throwable.getCause();
            }
            lines.add(line.toString());
        }
        for (String secret : secrets) {
            assertThat(lines).as("log lines must not carry '%s'", secret.length() > 4 ? secret.substring(0, 4) + "..." : secret)
                    .noneMatch(line -> line.contains(secret));
        }
        assertThat(lines).as("no e-mail address in any log line of the flows").noneMatch(line -> line.contains("@example.com"));
    }

    private List<String> limitedLogs() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(message -> message.startsWith("signup.resend.limited")).toList();
    }

    private List<String> capLogs() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("signup.resend.limited") || message.startsWith("mail.resend.capped")).toList();
    }

    // ================================================================== helpers: HTTP

    private Map<String, Object> signupBody(String cnpj, String login, String cpf, String terms, String phone, String billingEmail, String email) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("firm", Map.of("name", "Escritório Fictício QA", "documentNumber", cnpj, "billingEmail", billingEmail, "contactPhone", phone));
        request.put("user", Map.of("name", "Dona Fictícia QA", "document", cpf, "login", login, "email", email, "password", PASSWORD));
        if (terms != null) {
            request.put("termsVersion", terms);
        }
        return request;
    }

    private MvcResult signup(String cnpj, String login, String cpf, String ip, String terms, String phone, String billingEmail, String email) throws Exception {
        secrets.add(cnpj);
        secrets.add(cpf);
        secrets.add(login);
        secrets.add(ip);
        signupCnpjs.add(cnpj);
        return mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", ip)
                .content(json.writeValueAsString(signupBody(cnpj, login, cpf, terms, phone, billingEmail, email)))).andReturn();
    }

    private MvcResult verify(String token) throws Exception {
        secrets.add(token);
        return mvc.perform(post(BASE + "/signup/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token)))).andReturn();
    }

    private MvcResult resend(String login, String ip) throws Exception {
        secrets.add(ip);
        return mvc.perform(post(BASE + "/signup/resend").contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", ip)
                .content(json.writeValueAsString(Map.of("login", login)))).andReturn();
    }

    private MvcResult authenticate(String login, String password) throws Exception {
        String auth = Base64.getEncoder().encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        return mvc.perform(post(BASE + "/auth").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("auth", auth)))).andReturn();
    }

    private String bearer(MvcResult authenticated) throws Exception {
        return body(authenticated, 200).get("token").asText();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String bearer) {
        return request.header("Authorization", "Bearer " + bearer);
    }

    private MvcResult platformAction(String platformBearer, String firmId, String action, Map<String, Object> request) throws Exception {
        return mvc.perform(auth(post(BASE + "/platform/firms/" + firmId + "/access/" + action), platformBearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andReturn();
    }

    private JsonNode firmRow(String platformBearer, String cnpj) throws Exception {
        return body(mvc.perform(auth(get(BASE + "/platform/firms").param("q", cnpj), platformBearer)).andReturn(), 200).get("content").get(0);
    }

    private MvcResult createCompany(String bearer, String cnpj, boolean active) {
        return createCompanyNamed(bearer, cnpj, "Empresa Fictícia QA", active);
    }

    private MvcResult createCompanyNamed(String bearer, String cnpj, String name) {
        return createCompanyNamed(bearer, cnpj, name, true);
    }

    private MvcResult createCompanyNamed(String bearer, String cnpj, String name, boolean active) {
        try {
            Map<String, Object> company = Map.of("documentNumber", cnpj, "documentType", "CNPJ", "name", name, "active", active);
            return mvc.perform(auth(post(BASE + "/companies"), bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(company))).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MvcResult updateCompanyActive(String bearer, String companyId, boolean active) throws Exception {
        JsonNode current = body(mvc.perform(auth(get(BASE + "/companies/" + companyId), bearer)).andReturn(), 200);
        Map<String, Object> dto = json.convertValue(current, Map.class);
        dto.put("active", active);
        return mvc.perform(auth(MockMvcRequestBuilders.put(BASE + "/companies/" + companyId), bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(dto))).andReturn();
    }

    private MvcResult createUser(String bearer) {
        return createUserNamed(bearer, "Colaborador Fictício QA");
    }

    private MvcResult createUserNamed(String bearer, String name) {
        try {
            String login = "qa.colab." + digits(8);
            Map<String, Object> teammate = Map.of("name", name, "documentNumber", cpf(), "documentType", "CPF", "login", login,
                    "password", PASSWORD, "email", login + "@example.com", "profile", "EMPLOYEE", "accessProfiles", List.of("FISCAL"));
            return mvc.perform(auth(post(BASE + "/users"), bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(teammate))).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void userCreateNamed(String bearer, String name) throws Exception {
        body(createUserNamed(bearer, name), 200);
    }

    private interface Call {
        MvcResult run() throws Exception;
    }

    private static int statusOf(Call call) {
        try {
            return call.run().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String bodyOf(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode body(MvcResult result, int expectedStatus) throws Exception {
        String content = bodyOf(result);
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(expectedStatus);
        return json.readTree(content.isEmpty() ? "{}" : content);
    }

    private String code(MvcResult result, int expectedStatus) throws Exception {
        return body(result, expectedStatus).get("code").asText();
    }

    private String id(MvcResult result, int expectedStatus) throws Exception {
        return body(result, expectedStatus).get("id").asText();
    }

    private String tokenOf(MailMessage message) {
        Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(message.body());
        assertThat(matcher.find()).isTrue();
        secrets.add(matcher.group(1));
        return matcher.group(1);
    }

    private MailMessage lastMailTo(String email) {
        return mail.sent.stream().filter(message -> message.to().contains(email)).reduce((a, b) -> b).orElseThrow();
    }

    // ================================================================== helpers: database

    private String firmIdOf(String cnpj) {
        return jdbc.queryForObject("SELECT id FROM accounting_firms WHERE document_number = ?", String.class, cnpj);
    }

    private String userId(String login) {
        return jdbc.queryForObject("SELECT id FROM users WHERE login = ?", String.class, login);
    }

    private String subscriptionStatus(String cnpj) {
        return jdbc.queryForObject("SELECT status FROM firm_subscriptions WHERE accounting_firm_id = (SELECT id FROM accounting_firms"
                + " WHERE document_number = ?)", String.class, cnpj);
    }

    private long inUse(String firmId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE accounting_firm_id = ? AND deleted = 0 AND (active IS NULL OR active = 1)",
                Long.class, firmId);
    }

    /** A firm and its ADMIN straight in the database; {@code status} null = no firm_subscription row. */
    private void rawFirm(String firmId, String name, String adminLogin, String status) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', ?)", firmId, now, name);
        rawFirms.add(firmId);
        String documentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CPF')",
                documentId, now, cpf());
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login, name,"
                        + " email, password) VALUES (?, 0, ?, 'qa', 'ADMIN', ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), now, firmId, documentId, adminLogin, "Admin " + name, adminLogin + "@example.com",
                passwordEncoder.encode(PASSWORD));
        if (status != null) {
            jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, migrated, source, deleted, created_by, created_date)"
                    + " VALUES (?, ?, ?, 0, 'MANUAL', 0, 'qa', ?)", UUID.randomUUID().toString(), firmId, status, now);
        }
    }

    /** A client company straight in the database (document + company); returns the company id. */
    private String insertCompany(String firmId, String cnpj, boolean active) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String documentId = UUID.randomUUID().toString();
        String companyId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')",
                documentId, now, cnpj);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, 'Empresa Fictícia QA', ?)", companyId, now, firmId, documentId, active ? 1 : 0);
        return companyId;
    }

    private void bulkCompanies(String firmId, int count) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> documents = new ArrayList<>();
        List<Object[]> companies = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            String documentId = UUID.randomUUID().toString();
            String cnpj;
            do {
                cnpj = cnpj();
            } while (!used.add(cnpj));
            documents.add(new Object[]{documentId, now, cnpj});
            companies.add(new Object[]{UUID.randomUUID().toString(), now, firmId, documentId});
        }
        jdbc.batchUpdate("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')", documents);
        jdbc.batchUpdate("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, 'Empresa Fictícia QA', 1)", companies);
    }

    /** Apaga os escritórios do teste (cadastrados e criados no banco, o da plataforma incluso) com tudo o que pende deles. */
    private void cleanUp() {
        List<String> firms = new ArrayList<>(rawFirms);
        firms.add(PLATFORM_FIRM);
        for (String cnpj : signupCnpjs) {
            firms.addAll(jdbc.queryForList("SELECT id FROM accounting_firms WHERE document_number = ?", String.class, cnpj));
        }
        firms.addAll(jdbc.queryForList("SELECT accounting_firm_id FROM users WHERE login IN (?, ?)", String.class, PLATFORM_LOGIN, OTHER_RESERVED_LOGIN));
        for (String firm : firms.stream().distinct().toList()) {
            List<Map<String, Object>> users = jdbc.queryForList("SELECT id, document_id FROM users WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> user : users) {
                jdbc.update("DELETE FROM email_verifications WHERE user_id = ?", user.get("id"));
                jdbc.update("DELETE FROM user_access_profiles WHERE user_id = ?", user.get("id"));
                jdbc.update("DELETE FROM users_companies WHERE user_id = ?", user.get("id"));
            }
            jdbc.update("DELETE FROM terms_acceptances WHERE accounting_firm_id = ?", firm);
            jdbc.update("DELETE FROM firm_notifications WHERE accounting_firm_id = ?", firm);
            jdbc.update("DELETE FROM firm_subscription_events WHERE accounting_firm_id = ?", firm);
            jdbc.update("DELETE FROM firm_subscriptions WHERE accounting_firm_id = ?", firm);
            List<Map<String, Object>> companies = jdbc.queryForList("SELECT id, document_id FROM companies WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> company : companies) {
                jdbc.update("DELETE FROM company_tax_regimes WHERE company_id = ?", company.get("id"));
                jdbc.update("DELETE FROM users_companies WHERE company_id = ?", company.get("id"));
            }
            jdbc.update("DELETE FROM companies WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> company : companies) {
                jdbc.update("DELETE FROM documents WHERE id = ?", company.get("document_id"));
            }
            jdbc.update("DELETE FROM users WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> user : users) {
                jdbc.update("DELETE FROM documents WHERE id = ?", user.get("document_id"));
            }
            jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firm);
        }
        orphanEventFirms.forEach(firm -> jdbc.update("DELETE FROM firm_subscription_events WHERE accounting_firm_id = ?", firm));
        orphanEventFirms.clear();
        signupCnpjs.clear();
        rawFirms.clear();
    }

    // ================================================================== helpers: fictitious data

    private static String ip(int last) {
        return "203.0.113." + last;
    }

    private static String login() {
        return "qa.dona." + digits(10);
    }

    private static String digits(int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            digits.append(RANDOM.nextInt(10));
        }
        return digits.toString();
    }

    /** CNPJ fictício com dígitos verificadores válidos, novo a cada chamada. */
    private static String cnpj() {
        return withCheckDigits("9" + digits(7) + "0001");
    }

    private static String withCheckDigits(String twelve) {
        int d1 = cnpjDigit(twelve, new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = cnpjDigit(twelve + d1, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return twelve + d1 + d2;
    }

    private static int cnpjDigit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    /** CPF fictício com dígitos verificadores válidos (e que não é uma sequência repetida). */
    private static String cpf() {
        String base;
        do {
            base = digits(9);
        } while (base.chars().distinct().count() == 1);
        int d1 = cpfDigit(base, 10);
        int d2 = cpfDigit(base + d1, 11);
        return base + d1 + d2;
    }

    private static int cpfDigit(String digits, int firstWeight) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * (firstWeight - i);
        }
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }
}
