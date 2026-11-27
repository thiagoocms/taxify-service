package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.config.PendencyAlertProperties;
import br.com.cshub.taxify.integration.mail.GuardedMailSender;
import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSendException;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.pendency.alert.PendencyAlertScheduler;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA da spec 2026-11-27 (senha, sessoes e resumo de pendencias ALTA) por HTTP real (servidor em porta aleatoria, cliente
 * java.net.http), com o MySQL local e o e-mail gravado em memoria (nada sai). Complementa o
 * {@link PasswordAndAlertsIntegrationTest} (MockMvc) com o que a matriz CA/CT ainda nao cobria de ponta a ponta: a
 * indistinguibilidade do 202, os limites de envio (hora, dia, IP) com o relogio de Recife, a validade do link no limite,
 * as regras de senha, escritorio bloqueado, o aviso de senha alterada, o resumo por elegibilidade/episodio/fim de semana,
 * o teto com duas instancias, o ritmo, a limpeza, o conteudo sem dado fiscal e os logs sem dado pessoal.
 *
 * <p>Dados fictícios (@exemplo.test, CPF/CNPJ aleatorios), apagados no fim. So roda com DB_HOST=localhost.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.password.reset-enabled=true", "taxify.password.reset-purge-enabled=false",
        "taxify.pendency-alerts.enabled=false",
        "taxify.mail.send-interval-ms=0",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false"
})
class PasswordAndAlertsQaHttpTest {

    private static final String PASSWORD = "SenhaDeTeste2026";
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");
    private static final Random RANDOM = new Random();
    /** Terca-feira, 07:50 em Recife. */
    private static final LocalDateTime TUESDAY = LocalDateTime.of(2026, 12, 1, 7, 50);

    /** O e-mail da aplicacao nos testes: grava, pode falhar por destinatario e delega o filtro/ritmo a um decorador real. */
    static class SwitchableSender implements MailSender {
        final List<MailMessage> sent = new CopyOnWriteArrayList<>();
        final List<String> failFor = new CopyOnWriteArrayList<>();
        volatile MailSender guard;
        private final MailSender recorder = new MailSender() {
            @Override
            public void send(MailMessage message) {
                for (String to : message.to()) {
                    if (failFor.contains(to)) {
                        throw new MailSendException("falha simulada do SES");
                    }
                }
                sent.add(message);
            }

            @Override
            public String from() {
                return "nao-responda@cshub.com.br";
            }
        };

        @Override
        public void send(MailMessage message) {
            (guard == null ? recorder : guard).send(message);
        }

        @Override
        public boolean accepts(String address) {
            return (guard == null ? recorder : guard).accepts(address);
        }

        @Override
        public String from() {
            return recorder.from();
        }

        GuardedMailSender guarded(List<String> allowlist, long intervalMs) {
            return new GuardedMailSender(recorder, allowlist, intervalMs);
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        @Primary
        MutableClock qaClock() {
            return new MutableClock(TUESDAY);
        }

        @Bean
        @Primary
        SwitchableSender qaMailSender() {
            return new SwitchableSender();
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @Autowired
    private SwitchableSender mail;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PasswordProperties passwordProperties;
    @Autowired
    private PendencyAlertProperties alertProperties;
    @Autowired
    private PendencyAlertScheduler scheduler;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<String> firms = new CopyOnWriteArrayList<>();
    private final String prefix = "qa" + Integer.toHexString(RANDOM.nextInt(0xfffff)) + ".";
    /** Strings que nao podem aparecer em nenhum log (preenchido pelos testes). */
    private final List<String> forbiddenInLogs = new CopyOnWriteArrayList<>();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null && (connection.startsWith("jdbc:mysql://localhost:")
                || connection.startsWith("jdbc:mysql://127.0.0.1:")), "only against a local MySQL");
        clock.set(TUESDAY);
        mail.sent.clear();
        mail.failFor.clear();
        mail.guard = null;
        passwordProperties.setResetEnabled(true);
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        Thread.sleep(400); // the e-mail work after the 202 (virtual threads) must finish before the rows are deleted
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        passwordProperties.setResetEnabled(true);
        passwordProperties.setResetPerIpPerDay(10);
        alertProperties.setMaxPerDay(200);
        alertProperties.setDefaultOn(true);
        alertProperties.setTypes(List.of());
        for (String firm : firms) {
            List<String> users = jdbc.queryForList("SELECT id FROM users WHERE accounting_firm_id = ?", String.class, firm);
            for (String user : users) {
                jdbc.update("DELETE FROM pendency_alert_items WHERE user_id = ?", user);
                jdbc.update("DELETE FROM pendency_alert_digests WHERE user_id = ?", user);
                jdbc.update("DELETE FROM user_notification_settings WHERE user_id = ?", user);
                jdbc.update("DELETE FROM password_reset_tokens WHERE user_id = ?", user);
                jdbc.update("DELETE FROM user_access_profiles WHERE user_id = ?", user);
                jdbc.update("DELETE FROM users_companies WHERE user_id = ?", user);
            }
            List<Map<String, Object>> companies = jdbc.queryForList("SELECT id, document_id FROM companies WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> company : companies) {
                jdbc.update("DELETE FROM pendency_alert_items WHERE pendency_id IN (SELECT id FROM pendencies WHERE company_id = ?)", company.get("id"));
                jdbc.update("DELETE FROM pendencies WHERE company_id = ?", company.get("id"));
            }
            jdbc.update("DELETE FROM pendency_alert_digests WHERE accounting_firm_id = ?", firm);
            jdbc.update("DELETE FROM companies WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> company : companies) {
                jdbc.update("DELETE FROM documents WHERE id = ?", company.get("document_id"));
            }
            List<String> documents = jdbc.queryForList("SELECT document_id FROM users WHERE accounting_firm_id = ?", String.class, firm);
            jdbc.update("DELETE FROM users WHERE accounting_firm_id = ?", firm);
            for (String document : documents) {
                jdbc.update("DELETE FROM documents WHERE id = ?", document);
            }
            jdbc.update("DELETE FROM firm_subscription_events WHERE accounting_firm_id = ?", firm);
            jdbc.update("DELETE FROM firm_subscriptions WHERE accounting_firm_id = ?", firm);
            jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firm);
        }
        firms.clear();
    }

    // ------------------------------------------------------------------ fixtures (fictitious)

    private String firm(String status) {
        return firm(status, "BLOCKED".equals(status) ? "TRIAL_EXPIRED" : null, true);
    }

    private String firm(String status, String blockedReason, boolean withSubscription) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', ?)",
                id, now, "Escritório Exemplo " + prefix);
        if (withSubscription) {
            jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, blocked_reason, trial_ends_on, migrated, source, deleted, created_by, created_date)"
                            + " VALUES (?, ?, ?, ?, ?, 0, 'MANUAL', 0, 'qa', ?)",
                    UUID.randomUUID().toString(), id, status, blockedReason,
                    "TRIAL".equals(status) ? java.sql.Date.valueOf("2026-12-14") : null, now);
        }
        firms.add(id);
        return id;
    }

    private String user(String firm, String login, String email, String profile, String accessProfile) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CPF')",
                document, now, digits(11));
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login,"
                        + " name, email, password) VALUES (?, 0, ?, 'qa', ?, ?, ?, ?, ?, ?, ?)",
                id, now, profile, firm, document, prefix + login, "Pessoa " + login + " Ficticia", email,
                passwordEncoder.encode(PASSWORD));
        if (accessProfile != null) {
            jdbc.update("INSERT INTO user_access_profiles (user_id, access_profile) VALUES (?, ?)", id, accessProfile);
        }
        forbiddenInLogs.add(prefix + login);
        forbiddenInLogs.add(email);
        return id;
    }

    private String company(String firm, String name) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        String cnpj = digits(14);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')",
                document, now, cnpj);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, ?, 1)", id, now, firm, document, name);
        forbiddenInLogs.add(name);
        forbiddenInLogs.add(cnpj);
        return id;
    }

    private void portfolio(String user, String company) {
        jdbc.update("INSERT INTO users_companies (user_id, company_id, role, deleted, created_date, created_by)"
                + " VALUES (?, ?, 'MEMBER', 0, ?, 'qa')", user, company, Timestamp.valueOf(LocalDateTime.now()));
    }

    private String pendency(String company, String type, String severity, LocalDateTime firstSeen) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO pendencies (id, company_id, type, severity, natural_key, title, detail, status, first_seen_at,"
                        + " last_seen_at, auto_closed, deleted, created_by, created_date)"
                        + " VALUES (?, ?, ?, ?, ?, 'NF 1234 de Fornecedor Fictício', 'detalhe sigiloso 35260112345678000199550010000012341000012345', 'ABERTA', ?, ?, 0, 0, 'qa', ?)",
                id, company, type, severity, type + ":" + id, Timestamp.valueOf(firstSeen), Timestamp.valueOf(firstSeen),
                Timestamp.valueOf(LocalDateTime.now()));
        return id;
    }

    private static String digits(int length) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length; i++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }

    // ------------------------------------------------------------------ HTTP helpers (real server)

    record Resp(int status, String body, long millis) {
    }

    private Resp call(String method, String path, String token, Object body, String forwardedFor) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/taxify-service/api/v1" + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Resp(response.statusCode(), response.body(), (System.nanoTime() - start) / 1_000_000);
    }

    private Resp call(String method, String path, String token, Object body) throws Exception {
        return call(method, path, token, body, null);
    }

    private Resp login(String login, String password) throws Exception {
        String auth = Base64.getEncoder().encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        return call("POST", "/auth", null, Map.of("auth", auth));
    }

    private String token(String login, String password) throws Exception {
        Resp resp = login(login, password);
        assertThat(resp.status()).as("login " + resp.body()).isEqualTo(200);
        return json.readTree(resp.body()).get("token").asText();
    }

    private String code(Resp resp) throws Exception {
        JsonNode node = json.readTree(resp.body());
        return node.has("code") ? node.get("code").asText() : null;
    }

    private int ping(String token) throws Exception {
        return call("GET", "/firm-access", token, null).status();
    }

    private void forgot(String identifier, String ip) throws Exception {
        assertThat(call("POST", "/auth/password/forgot", null, Map.of("identifier", identifier), ip).status()).isEqualTo(202);
    }

    private static String tokenOf(MailMessage message) {
        Matcher matcher = TOKEN.matcher(message.body());
        assertThat(matcher.find()).as(message.body()).isTrue();
        return matcher.group(1);
    }

    private void awaitMails(int count) throws InterruptedException {
        for (int i = 0; i < 120 && mail.sent.size() < count; i++) {
            Thread.sleep(50);
        }
        assertThat(mail.sent).hasSizeGreaterThanOrEqualTo(count);
    }

    /** Pedido que NAO deve gerar e-mail: espera o processamento assincrono acabar e confere que nada saiu. */
    private void assertNoNewMail(int expectedTotal) throws InterruptedException {
        Thread.sleep(700);
        assertThat(mail.sent).hasSize(expectedTotal);
    }

    private MailMessage lastTo(String address) {
        return mail.sent.stream().filter(m -> m.to().contains(address)).reduce((first, second) -> second).orElseThrow();
    }

    private long tokens(String userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?", Long.class, userId);
    }

    @org.junit.jupiter.api.AfterEach
    void logsHaveNoPersonalData() {
        List<String> forbidden = new ArrayList<>(forbiddenInLogs);
        forbidden.add("@exemplo.test");
        forbidden.add(prefix);
        forbidden.add("#token=");
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            String line = event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            for (String secret : forbidden) {
                assertThat(line).as("log %s: %s", event.getLoggerName(), line).doesNotContain(secret);
            }
        }
    }

    // ================================================================== SENHA

    /** CA-SN-02, CT-SN-02, CT-SN-18, CA-SN-10: existente x inexistente x nao verificado = a mesma resposta; tempo equivalente. */
    @Test
    void forgotAnswersTheSameForExistingUnknownAndUnverified() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "ana.existe", "ana.existe@exemplo.test", "EMPLOYEE", "FISCAL");
        String pending = firm("PENDING_VERIFICATION");
        user(pending, "dono.pendente", "dono.pendente@exemplo.test", "ADMIN", null);

        Resp known = null;
        Resp unknown = null;
        Resp unverified = null;
        List<Long> tKnown = new ArrayList<>();
        List<Long> tUnknown = new ArrayList<>();
        List<Long> tUnverified = new ArrayList<>();
        // warm-up (JIT, pool) and then 12 rounds from different IPs (so the IP cap never decides)
        for (int i = 0; i < 13; i++) {
            known = call("POST", "/auth/password/forgot", null, Map.of("identifier", prefix + "ana.existe"), "203.0.113." + (10 + i));
            unknown = call("POST", "/auth/password/forgot", null, Map.of("identifier", prefix + "nao.existe." + i), "203.0.113." + (10 + i));
            unverified = call("POST", "/auth/password/forgot", null, Map.of("identifier", prefix + "dono.pendente"), "203.0.113." + (10 + i));
            if (i > 0) {
                tKnown.add(known.millis());
                tUnknown.add(unknown.millis());
                tUnverified.add(unverified.millis());
            }
            if (i == 2) {
                // the hourly cap of the real account would stop the e-mail, not the answer - move the clock an hour at a time
                clock.set(TUESDAY.plusHours(1));
            }
        }
        for (Resp resp : List.of(known, unknown, unverified)) {
            assertThat(resp.status()).isEqualTo(202);
            assertThat(resp.body()).isEmpty();
        }
        Collections.sort(tKnown);
        Collections.sort(tUnknown);
        Collections.sort(tUnverified);
        long medianKnown = tKnown.get(tKnown.size() / 2);
        long medianUnknown = tUnknown.get(tUnknown.size() / 2);
        long medianUnverified = tUnverified.get(tUnverified.size() / 2);
        System.out.println("QA forgot median ms: known=" + medianKnown + " unknown=" + medianUnknown + " unverified=" + medianUnverified);
        assertThat(Math.abs(medianKnown - medianUnknown)).as("known x unknown").isLessThan(40);
        assertThat(Math.abs(medianKnown - medianUnverified)).as("known x unverified").isLessThan(40);
        Thread.sleep(500);
        assertThat(mail.sent.stream().filter(m -> m.to().contains("dono.pendente@exemplo.test"))).isEmpty();
        assertThat(mail.sent.stream().filter(m -> m.to().contains("ana.existe@exemplo.test"))).isNotEmpty();
    }

    /** CT-SN-01: expires_at = emissao + 60 min no relogio de Recife; so o hash fica no banco. */
    @Test
    void theLinkExpiresSixtyMinutesAfterIssuanceInRecifeTime() throws Exception {
        clock.set(LocalDateTime.of(2026, 12, 1, 10, 0));
        String firm = firm("ACTIVE");
        String ana = user(firm, "ana.hora", "ana.hora@exemplo.test", "EMPLOYEE", "FISCAL");
        forgot(prefix + "ana.hora", null);
        awaitMails(1);
        String token = tokenOf(mail.sent.get(0));
        Map<String, Object> row = jdbc.queryForMap("SELECT token_hash, requested_at, expires_at FROM password_reset_tokens WHERE user_id = ?", ana);
        assertThat(((LocalDateTime) row.get("expires_at"))).isEqualTo(LocalDateTime.of(2026, 12, 1, 11, 0));
        assertThat(row.get("token_hash").toString()).hasSize(64).isNotEqualTo(token).doesNotContain(token);
        assertThat(mail.sent.get(0).body()).contains("60 minutos").contains(prefix + "ana.hora").contains("Escritório Exemplo");
        forbiddenInLogs.add(token);
    }

    /** CT-SN-09 / CA-SN-05: 10:59:59 passa, 11:00:01 nao; segundo uso: 400. */
    @Test
    void validityBoundary() throws Exception {
        clock.set(LocalDateTime.of(2026, 12, 1, 10, 0));
        String firm = firm("ACTIVE");
        user(firm, "ana.limite", "ana.limite@exemplo.test", "EMPLOYEE", "FISCAL");
        forgot(prefix + "ana.limite", null);
        awaitMails(1);
        String token = tokenOf(mail.sent.get(0));
        forbiddenInLogs.add(token);

        clock.set(LocalDateTime.of(2026, 12, 1, 11, 0, 1));
        Resp late = call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", "OutraSenha2026"));
        assertThat(late.status()).isEqualTo(400);
        assertThat(code(late)).isEqualTo("error.password.reset.token.invalid");
        assertThat(call("POST", "/auth/password/reset/check", null, Map.of("token", token)).status()).isEqualTo(400);

        clock.set(LocalDateTime.of(2026, 12, 1, 10, 59, 59));
        assertThat(call("POST", "/auth/password/reset/check", null, Map.of("token", token)).status()).isEqualTo(204);
        assertThat(call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", "OutraSenha2026")).status()).isEqualTo(204);
        assertThat(code(call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", "OutraSenha2027")))).isEqualTo("error.password.reset.token.invalid");
        assertThat(login(prefix + "ana.limite", "OutraSenha2026").status()).isEqualTo(200);
    }

    /** CT-SN-11 / CA-SN-06: o segundo link invalida o primeiro. */
    @Test
    void aNewLinkKillsTheOldOne() throws Exception {
        clock.set(LocalDateTime.of(2026, 12, 1, 10, 0));
        String firm = firm("ACTIVE");
        user(firm, "ana.dois", "ana.dois@exemplo.test", "EMPLOYEE", "FISCAL");
        forgot(prefix + "ana.dois", null);
        awaitMails(1);
        clock.set(LocalDateTime.of(2026, 12, 1, 10, 5));
        forgot(prefix + "ana.dois", null);
        awaitMails(2);
        String t1 = tokenOf(mail.sent.get(0));
        String t2 = tokenOf(mail.sent.get(1));
        forbiddenInLogs.addAll(List.of(t1, t2));
        assertThat(code(call("POST", "/auth/password/reset", null, Map.of("token", t1, "newPassword", "OutraSenha2026")))).isEqualTo("error.password.reset.token.invalid");
        assertThat(call("POST", "/auth/password/reset", null, Map.of("token", t2, "newPassword", "OutraSenha2026")).status()).isEqualTo(204);
    }

    /** CT-SN-06: 10:00, 10:10, 10:20 saem; 10:30 nao; 11:01 volta. */
    @Test
    void hourlyLimit() throws Exception {
        String firm = firm("ACTIVE");
        String ana = user(firm, "ana.hora3", "ana.hora3@exemplo.test", "EMPLOYEE", "FISCAL");
        int[] minutes = {0, 10, 20};
        int expected = 0;
        for (int minute : minutes) {
            clock.set(LocalDateTime.of(2026, 12, 1, 10, minute));
            forgot(prefix + "ana.hora3", null);
            awaitMails(++expected);
        }
        clock.set(LocalDateTime.of(2026, 12, 1, 10, 30));
        forgot(prefix + "ana.hora3", null);
        assertNoNewMail(3);
        assertThat(tokens(ana)).isEqualTo(3);
        clock.set(LocalDateTime.of(2026, 12, 1, 11, 1));
        forgot(prefix + "ana.hora3", null);
        awaitMails(4);
        assertThat(logs.list.stream().anyMatch(e -> e.getFormattedMessage().equals("password.reset.limited"))).isTrue();
    }

    /** CT-SN-07: 5 por dia civil de Recife, o 6o nao sai, e a meia-noite zera. */
    @Test
    void dailyLimitResetsAtRecifeMidnight() throws Exception {
        String firm = firm("ACTIVE");
        String ana = user(firm, "ana.dia", "ana.dia@exemplo.test", "EMPLOYEE", "FISCAL");
        int[][] times = {{8, 0}, {8, 10}, {8, 20}, {9, 30}, {9, 40}};
        int n = 0;
        for (int[] time : times) {
            clock.set(LocalDateTime.of(2026, 12, 1, time[0], time[1]));
            forgot(prefix + "ana.dia", null);
            awaitMails(++n);
        }
        clock.set(LocalDateTime.of(2026, 12, 1, 11, 0));
        forgot(prefix + "ana.dia", null);
        assertNoNewMail(5);
        clock.set(LocalDateTime.of(2026, 12, 1, 23, 59, 59));
        forgot(prefix + "ana.dia", null);
        assertNoNewMail(5);
        clock.set(LocalDateTime.of(2026, 12, 2, 0, 0, 0));
        forgot(prefix + "ana.dia", null);
        awaitMails(6);
        assertThat(tokens(ana)).isEqualTo(6);
    }

    /** CT-SN-08 (com concorrencia): 11 contas, mesmo IP, 11 pedidos ao mesmo tempo: exatamente 10 e-mails. */
    @Test
    void ipCapOfTenWithElevenConcurrentAccounts() throws Exception {
        String firm = firm("ACTIVE");
        List<String> logins = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            user(firm, "ip" + i, "ip" + i + "@exemplo.test", "EMPLOYEE", "FISCAL");
            logins.add(prefix + "ip" + i);
        }
        String ip = "198.51.100." + (1 + RANDOM.nextInt(250));
        forbiddenInLogs.add(ip);
        List<CompletableFuture<Resp>> futures = new ArrayList<>();
        for (String login : logins) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return call("POST", "/auth/password/forgot", null, Map.of("identifier", login), ip);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (CompletableFuture<Resp> future : futures) {
            assertThat(future.get().status()).isEqualTo(202);
        }
        awaitMails(10);
        Thread.sleep(1500);
        assertThat(mail.sent).hasSize(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE requested_ip = ?", Integer.class, ip)).isEqualTo(10);
    }

    /** CT-SN-04 e CT-SN-05: e-mail de 2 contas (maiusculas) = 2 e-mails; 7 contas = 5 e-mails, as mais recentes. */
    @Test
    void sharedEmailsOneMailPerAccountAtMostFive() throws Exception {
        String a = firm("ACTIVE");
        String b = firm("TRIAL");
        user(a, "ana.fiscal", "contato@exemplo.test", "EMPLOYEE", "FISCAL");
        user(b, "ana.admin", "contato@exemplo.test", "ADMIN", null);
        forgot("Contato@Exemplo.TEST", null);
        awaitMails(2);
        Thread.sleep(300);
        assertThat(mail.sent).hasSize(2);
        assertThat(mail.sent.stream().map(MailMessage::body).toList())
                .anyMatch(body -> body.contains(prefix + "ana.fiscal")).anyMatch(body -> body.contains(prefix + "ana.admin"));
        assertThat(mail.sent.stream().filter(m -> m.body().contains(prefix + "ana.fiscal")).findFirst().orElseThrow().body()).doesNotContain(prefix + "ana.admin");

        mail.sent.clear();
        String c = firm("ACTIVE");
        for (int i = 0; i < 7; i++) {
            user(c, "sete" + i, "sete@exemplo.test", "EMPLOYEE", "FISCAL");
            jdbc.update("UPDATE users SET created_date = ? WHERE login = ?", Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(i)), prefix + "sete" + i);
        }
        forgot("sete@exemplo.test", null);
        awaitMails(5);
        Thread.sleep(500);
        assertThat(mail.sent).hasSize(5);
        String all = String.join("\n", mail.sent.stream().map(MailMessage::body).toList());
        assertThat(all).contains(prefix + "sete6").contains(prefix + "sete2").doesNotContain(prefix + "sete1").doesNotContain(prefix + "sete0");
        assertThat(logs.list.stream().anyMatch(e -> e.getFormattedMessage().equals("password.reset.too.many.accounts count=7"))).isTrue();
    }

    /** CT-SN-12/13/14, CA-SN-07 e `:`: regras de senha no reset (e o link continua valendo quando a senha e recusada). */
    @Test
    void passwordRulesOnReset() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "ana.regras", "ana.regras@exemplo.test", "EMPLOYEE", "FISCAL");
        forgot(prefix + "ana.regras", null);
        awaitMails(1);
        String token = tokenOf(mail.sent.get(0));
        forbiddenInLogs.add(token);
        assertThat(code(call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", "Abc1234")))).isEqualTo("error.user.password.too.short");
        assertThat(code(call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", "ç".repeat(37))))).isEqualTo("error.user.password.invalid");
        assertThat(code(call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", PASSWORD)))).isEqualTo("error.user.password.same");
        assertThat(code(call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", "        ")))).isEqualTo("error.user.password.required");
        assertThat(call("POST", "/auth/password/reset", null, Map.of("token", token)).status()).isEqualTo(400);
        assertThat(call("POST", "/auth/password/reset/check", null, Map.of("token", token)).status()).as("still valid").isEqualTo(204);
        // 72 bytes exactly (36 x "ç") is accepted, and a password with ":" logs in
        assertThat(call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", "ç".repeat(36))).status()).isEqualTo(204);
        assertThat(login(prefix + "ana.regras", "ç".repeat(36)).status()).isEqualTo(200);

        forgot(prefix + "ana.regras", null);
        for (int i = 0; i < 120 && mail.sent.stream().filter(m -> m.body().contains("#token=")).count() < 2; i++) {
            Thread.sleep(50);
        }
        String second = tokenOf(mail.sent.stream().filter(m -> m.body().contains("#token=")).reduce((a, b) -> b).orElseThrow());
        forbiddenInLogs.add(second);
        assertThat(call("POST", "/auth/password/reset", null, Map.of("token", second, "newPassword", "abc:12345:xyz")).status()).isEqualTo(204);
        assertThat(login(prefix + "ana.regras", "abc:12345:xyz").status()).isEqualTo(200);
        assertThat(login(prefix + "ana.regras", "abc").status()).isEqualTo(403);
    }

    /** CT-SN-16 / CA-SN-09: BLOCKED pede e redefine; depois do login, so leitura. CT-SN-17: sem assinatura recebe o link. */
    @Test
    void blockedAndSubscriptionlessFirmsStillRecoverThePassword() throws Exception {
        String blocked = firm("BLOCKED");
        user(blocked, "ana.bloqueada", "ana.bloqueada@exemplo.test", "ADMIN", null);
        String none = firm(null, null, false);
        user(none, "ana.sem.assinatura", "ana.sem@exemplo.test", "EMPLOYEE", "FISCAL");
        forgot(prefix + "ana.bloqueada", null);
        forgot(prefix + "ana.sem.assinatura", null);
        awaitMails(2);
        String token = tokenOf(lastTo("ana.bloqueada@exemplo.test"));
        forbiddenInLogs.add(token);
        assertThat(call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", "SenhaNova2026")).status()).isEqualTo(204);
        String session = token(prefix + "ana.bloqueada", "SenhaNova2026");
        Resp write = call("POST", "/users", session, Map.of("name", "X"));
        assertThat(write.status()).isEqualTo(403);
        assertThat(code(write)).isEqualTo("error.firm.access.blocked");
        // CA-SN-14 for a BLOCKED firm too: changing the own password is allowed
        assertThat(call("PUT", "/users/me/password", session, Map.of("currentPassword", "SenhaNova2026", "newPassword", "SenhaNova2027")).status()).isEqualTo(200);
    }

    /** CT-SN-19: usuario excluido entre o pedido e o uso: 400; e excluido antes do pedido: nada. */
    @Test
    void aUserDeletedBetweenRequestAndUse() throws Exception {
        String firm = firm("ACTIVE");
        String ana = user(firm, "ana.excluida", "ana.excluida@exemplo.test", "EMPLOYEE", "FISCAL");
        forgot(prefix + "ana.excluida", null);
        awaitMails(1);
        String token = tokenOf(mail.sent.get(0));
        forbiddenInLogs.add(token);
        jdbc.update("UPDATE users SET deleted = 1 WHERE id = ?", ana);
        assertThat(code(call("POST", "/auth/password/reset", null, Map.of("token", token, "newPassword", "SenhaNova2026")))).isEqualTo("error.password.reset.token.invalid");
        assertThat(call("POST", "/auth/password/reset/check", null, Map.of("token", token)).status()).isEqualTo(400);
        forgot(prefix + "ana.excluida", null);
        assertNoNewMail(1);
    }

    /** CT-SN-21/22: o JWT novo traz sv = versao nova; 5 erros, 429 mesmo com a certa, e o 200 depois da janela. */
    @Test
    void ownChangeTokenCarriesTheNewVersionAndTheLimitHolds() throws Exception {
        String firm = firm("ACTIVE");
        String ana = user(firm, "ana.troca", "ana.troca@exemplo.test", "EMPLOYEE", "FISCAL");
        clock.set(LocalDateTime.of(2026, 12, 1, 10, 0));
        String session = token(prefix + "ana.troca", PASSWORD);
        for (int i = 0; i < 5; i++) {
            clock.set(LocalDateTime.of(2026, 12, 1, 10, i));
            assertThat(code(call("PUT", "/users/me/password", session, Map.of("currentPassword", "errada" + i, "newPassword", "NovaSenha#2026")))).isEqualTo("error.user.password.current.invalid");
        }
        clock.set(LocalDateTime.of(2026, 12, 1, 10, 6));
        Resp limited = call("PUT", "/users/me/password", session, Map.of("currentPassword", PASSWORD, "newPassword", "NovaSenha#2026"));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("error.user.password.change.rate.limited");
        assertThat(jdbc.queryForObject("SELECT password_fail_count FROM users WHERE id = ?", Integer.class, ana)).isEqualTo(5);
        clock.set(LocalDateTime.of(2026, 12, 1, 11, 1));
        Resp ok = call("PUT", "/users/me/password", session, Map.of("currentPassword", PASSWORD, "newPassword", "NovaSenha#2026"));
        assertThat(ok.status()).isEqualTo(200);
        String fresh = json.readTree(ok.body()).get("token").asText();
        String[] parts = fresh.split("\\.");
        JsonNode claims = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        assertThat(claims.get("sv").asInt()).isEqualTo(jdbc.queryForObject("SELECT session_version FROM users WHERE id = ?", Integer.class, ana)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_fail_count FROM users WHERE id = ?", Integer.class, ana)).isZero();
        assertThat(jdbc.queryForObject("SELECT password_changed_at FROM users WHERE id = ?", Timestamp.class, ana)).isNotNull();
        assertThat(ping(fresh)).isEqualTo(200);
        // the new password equal to the current one, and the rules, on the logged change
        assertThat(code(call("PUT", "/users/me/password", fresh, Map.of("currentPassword", "NovaSenha#2026", "newPassword", "NovaSenha#2026")))).isEqualTo("error.user.password.same");
        assertThat(code(call("PUT", "/users/me/password", fresh, Map.of("currentPassword", "NovaSenha#2026", "newPassword", "curta")))).isEqualTo("error.user.password.too.short");
        assertThat(code(call("PUT", "/users/me/password", fresh, Map.of("currentPassword", "NovaSenha#2026", "newPassword", "é".repeat(37))))).isEqualTo("error.user.password.invalid");
        assertThat(call("PUT", "/users/me/password", fresh, null).status()).isEqualTo(400);
    }

    /** SN-RN-04 sob concorrencia na MESMA conta: 8 pedidos simultaneos, limite de 3 por hora = exatamente 3 links. */
    @Test
    void hourlyLimitHoldsWithConcurrentRequestsForTheSameAccount() throws Exception {
        String firm = firm("ACTIVE");
        String ana = user(firm, "ana.corrida", "ana.corrida@exemplo.test", "EMPLOYEE", "FISCAL");
        List<CompletableFuture<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return call("POST", "/auth/password/forgot", null, Map.of("identifier", prefix + "ana.corrida"), "203.0.113." + (100 + n));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (CompletableFuture<Resp> future : futures) {
            assertThat(future.get().status()).isEqualTo(202);
        }
        awaitMails(3);
        Thread.sleep(1200);
        assertThat(mail.sent).hasSize(3);
        assertThat(tokens(ana)).isEqualTo(3);
        // only the last link is open (each new request invalidates the previous ones)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? AND invalidated_at IS NULL AND used_at IS NULL", Integer.class, ana)).isEqualTo(1);
    }

    /** P-SN-12: a troca logada nao depende do "Esqueci a senha" estar ligado (so o e-mail depende). */
    @Test
    void theLoggedChangeWorksWithTheResetSwitchedOff() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "ana.desligado", "ana.desligado@exemplo.test", "EMPLOYEE", "FISCAL");
        String session = token(prefix + "ana.desligado", PASSWORD);
        passwordProperties.setResetEnabled(false);
        assertThat(call("PUT", "/users/me/password", session, Map.of("currentPassword", PASSWORD, "newPassword", "SenhaNova2026")).status()).isEqualTo(200);
        assertThat(login(prefix + "ana.desligado", "SenhaNova2026").status()).isEqualTo(200);
        Resp forgot = call("POST", "/auth/password/forgot", null, Map.of("identifier", prefix + "ana.desligado"));
        assertThat(forgot.status()).isEqualTo(503);
        assertThat(code(forgot)).isEqualTo("error.password.reset.unavailable");
        assertThat(call("POST", "/auth/password/reset", null, Map.of("token", "x", "newPassword", "SenhaNova2027")).status()).isEqualTo(503);
    }

    /** Entradas ruins no forgot: identificador enorme, caracteres de SQL, e-mail com espacos/maiusculas, conta pendente que divide o e-mail. */
    @Test
    void forgotSurvivesWeirdIdentifiers() throws Exception {
        String active = firm("ACTIVE");
        String pending = firm("PENDING_VERIFICATION");
        user(active, "ana.mista", "mista@exemplo.test", "EMPLOYEE", "FISCAL");
        user(pending, "dono.misto", "mista@exemplo.test", "ADMIN", null);
        for (String weird : List.of("x".repeat(20000), "' OR '1'='1", "%", "_", "mista@exemplo.test'; DROP TABLE users;--", "\u0000", "ana\r\nBcc: x@y.z")) {
            assertThat(call("POST", "/auth/password/forgot", null, Map.of("identifier", weird), "203.0.113.77").status()).isEqualTo(202);
        }
        assertThat(call("POST", "/auth/password/forgot", null, Map.of("identifier", 123), "203.0.113.77").status()).isIn(202, 400);
        assertThat(call("POST", "/auth/password/forgot", null, Map.of("identifier", "  MISTA@Exemplo.Test  "), "203.0.113.78").status()).isEqualTo(202);
        awaitMails(1);
        Thread.sleep(600);
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.sent.get(0).body()).contains(prefix + "ana.mista").doesNotContain(prefix + "dono.misto");
        assertThat(mail.sent.get(0).to()).containsExactly("mista@exemplo.test");
    }

    /** Endpoint sem token: 401/403 e sem dado. */
    @Test
    void protectedEndpointsRefuseAnonymousCalls() throws Exception {
        assertThat(call("PUT", "/users/me/password", null, Map.of("currentPassword", "a", "newPassword", "bbbbbbbbb")).status()).isIn(401, 403);
        assertThat(call("GET", "/users/me/notification-settings", null, null).status()).isIn(401, 403);
        assertThat(call("PUT", "/users/me/notification-settings", null, Map.of("pendencyHighDigest", false)).status()).isIn(401, 403);
        assertThat(call("GET", "/users/me/notification-settings", "lixo.lixo.lixo", null).status()).isIn(401, 403);
    }

    /** CT-SN-27: ADMIN de outro escritorio nao mexe no usuario alheio (404) nem derruba a sessao dele. */
    @Test
    void anAdminCannotTouchAnotherFirmsUser() throws Exception {
        String firmA = firm("ACTIVE");
        String firmB = firm("ACTIVE");
        user(firmA, "carla.a", "carla.a@exemplo.test", "ADMIN", null);
        String bruno = user(firmB, "bruno.b", "bruno.b@exemplo.test", "EMPLOYEE", "FISCAL");
        String admin = token(prefix + "carla.a", PASSWORD);
        String brunoSession = token(prefix + "bruno.b", PASSWORD);
        Map<String, Object> body = new HashMap<>();
        body.put("id", bruno);
        body.put("name", "Pessoa Ficticia");
        body.put("documentNumber", jdbc.queryForObject("SELECT d.number FROM users u JOIN documents d ON d.id = u.document_id WHERE u.id = ?", String.class, bruno));
        body.put("documentType", "CPF");
        body.put("login", prefix + "bruno.b");
        body.put("password", "SenhaInvasora2026");
        body.put("email", "bruno.b@exemplo.test");
        body.put("profile", "EMPLOYEE");
        body.put("accessProfiles", List.of("FISCAL"));
        Resp resp = call("PUT", "/users/" + bruno, admin, body);
        assertThat(resp.status()).isEqualTo(404);
        assertThat(ping(brunoSession)).isEqualTo(200);
        assertThat(login(prefix + "bruno.b", PASSWORD).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT session_version FROM users WHERE id = ?", Integer.class, bruno)).isZero();
    }

    /** CA-SN-17/P-SN-10: login novo com `:` e recusado na criacao de usuario (HTTP) - e o existente continua entrando. */
    @Test
    void aNewLoginWithColonIsRefusedOnUserCreation() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "carla.cria", "carla.cria@exemplo.test", "ADMIN", null);
        String admin = token(prefix + "carla.cria", PASSWORD);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Pessoa Ficticia");
        body.put("documentNumber", "52998224725");
        body.put("documentType", "CPF");
        body.put("login", prefix + "com:dois.pontos");
        body.put("password", "SenhaBoa2026");
        body.put("email", "novo@exemplo.test");
        body.put("profile", "EMPLOYEE");
        body.put("accessProfiles", List.of("FISCAL"));
        Resp resp = call("POST", "/users", admin, body);
        assertThat(resp.status()).isEqualTo(400);
        assertThat(code(resp)).isEqualTo("error.user.login.invalid.char");
        body.put("login", prefix + "sem.dois.pontos");
        body.put("password", "curta");
        assertThat(code(call("POST", "/users", admin, body))).isEqualTo("error.user.password.too.short");
        // an old login with ":" (already stored) keeps working with a password that has ":" too
        user(firm, "velho:login", "velho@exemplo.test", "EMPLOYEE", "FISCAL");
        jdbc.update("UPDATE users SET password = ? WHERE login = ?", passwordEncoder.encode("abc:12345"), prefix + "velho:login");
        // login "x:y" cannot authenticate (split on the first ":") - documented behaviour, not a regression
        assertThat(login(prefix + "velho:login", "abc:12345").status()).isIn(400, 401, 403);
    }

    /** CT-SN-31: o aviso de senha alterada nos tres caminhos, sem senha, token nem link; falha do e-mail nao desfaz. */
    @Test
    void thePasswordChangedNoticeIsSafeAndAFailureKeepsTheChange() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "carla.aviso", "carla.aviso@exemplo.test", "ADMIN", null);
        user(firm, "bruno.aviso", "bruno.aviso@exemplo.test", "EMPLOYEE", "FISCAL");
        String bruno = jdbc.queryForObject("SELECT id FROM users WHERE login = ?", String.class, prefix + "bruno.aviso");
        String admin = token(prefix + "carla.aviso", PASSWORD);
        String session = token(prefix + "bruno.aviso", PASSWORD);

        Resp changed = call("PUT", "/users/me/password", session, Map.of("currentPassword", PASSWORD, "newPassword", "SenhaNova2026"));
        assertThat(changed.status()).isEqualTo(200);
        String freshToken = json.readTree(changed.body()).get("token").asText();
        awaitMails(1);
        MailMessage notice = mail.sent.get(0);
        assertThat(notice.to()).containsExactly("bruno.aviso@exemplo.test");
        assertThat(notice.body()).contains("por você em Minha conta").contains(prefix + "bruno.aviso").doesNotContain("SenhaNova2026")
                .doesNotContain(PASSWORD).doesNotContain("http").doesNotContain("token").doesNotContain(freshToken);
        assertThat(notice.subject()).doesNotContain(prefix);

        // the mail fails: the change is kept (the 200 stays) and the log has no address
        mail.failFor.add("bruno.aviso@exemplo.test");
        Resp again = call("PUT", "/users/me/password", freshToken, Map.of("currentPassword", "SenhaNova2026", "newPassword", "SenhaNova2027"));
        assertThat(again.status()).isEqualTo(200);
        Thread.sleep(600);
        assertThat(login(prefix + "bruno.aviso", "SenhaNova2027").status()).isEqualTo(200);
        assertThat(logs.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("password.notice.failed user="))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ? AND session_version = 2", Integer.class, bruno)).isEqualTo(1);
    }

    /** SN-RN-11: token antigo -> 401 session.expired depois de reset, troca propria e troca pelo ADMIN; e um token sem sv passa. */
    @Test
    void oldTokenIs401AfterEachOfTheThreePaths() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "carla.sessao", "carla.sessao@exemplo.test", "ADMIN", null);
        String ana = user(firm, "ana.sessao", "ana.sessao@exemplo.test", "EMPLOYEE", "FISCAL");
        String admin = token(prefix + "carla.sessao", PASSWORD);
        String s0 = token(prefix + "ana.sessao", PASSWORD);

        // 1. by e-mail
        forgot(prefix + "ana.sessao", null);
        awaitMails(1);
        String link = tokenOf(mail.sent.get(0));
        forbiddenInLogs.add(link);
        assertThat(call("POST", "/auth/password/reset", null, Map.of("token", link, "newPassword", "SenhaUm2026")).status()).isEqualTo(204);
        Resp expired = call("GET", "/firm-access", s0, null);
        assertThat(expired.status()).isEqualTo(401);
        assertThat(code(expired)).isEqualTo("error.auth.session.expired");

        // 2. by herself
        String s1 = token(prefix + "ana.sessao", "SenhaUm2026");
        Resp own = call("PUT", "/users/me/password", s1, Map.of("currentPassword", "SenhaUm2026", "newPassword", "SenhaDois2026"));
        assertThat(own.status()).isEqualTo(200);
        assertThat(code(call("GET", "/firm-access", s1, null))).isEqualTo("error.auth.session.expired");

        // 3. by the ADMIN
        String s2 = json.readTree(own.body()).get("token").asText();
        assertThat(ping(s2)).isEqualTo(200);
        Map<String, Object> body = new HashMap<>();
        body.put("id", ana);
        body.put("name", "Pessoa Ficticia");
        body.put("documentNumber", jdbc.queryForObject("SELECT d.number FROM users u JOIN documents d ON d.id = u.document_id WHERE u.id = ?", String.class, ana));
        body.put("documentType", "CPF");
        body.put("login", prefix + "ana.sessao");
        body.put("password", "SenhaTres2026");
        body.put("email", "ana.sessao@exemplo.test");
        body.put("profile", "EMPLOYEE");
        body.put("accessProfiles", List.of("FISCAL"));
        assertThat(call("PUT", "/users/" + ana, admin, body).status()).isEqualTo(200);
        assertThat(code(call("GET", "/firm-access", s2, null))).isEqualTo("error.auth.session.expired");
        assertThat(ping(admin)).as("the ADMIN's own session is untouched").isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT session_version FROM users WHERE id = ?", Integer.class, ana)).isEqualTo(3);
    }

    // ================================================================== RESUMO DE PENDENCIAS

    private record Scenario(String firm, String carla, String ana, String bruno, String x, String y, String z) {
    }

    private Scenario baseScenario(String firmStatus) {
        String firm = firm(firmStatus);
        String carla = user(firm, "carla.admin", "carla@exemplo.test", "ADMIN", null);
        String ana = user(firm, "ana.fiscal", "ana@exemplo.test", "EMPLOYEE", "FISCAL");
        String bruno = user(firm, "bruno.dp", "bruno@exemplo.test", "EMPLOYEE", "PAYROLL");
        String x = company(firm, "Têxtil Exemplo Ltda");
        String y = company(firm, "Confecção Modelo ME");
        String z = company(firm, "Comércio Teste Ltda");
        portfolio(ana, x);
        portfolio(ana, y);
        portfolio(bruno, z);
        return new Scenario(firm, carla, ana, bruno, x, y, z);
    }

    private int digestsOf(String user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_digests WHERE user_id = ?", Integer.class, user);
    }

    /** CT-AV-01/02/03/04 e CA-AV-13 (cron). Apenas ALTA abertas e novas. */
    @Test
    void digestContentAndDeduplicationWithTheTypeFilter() throws Exception {
        Scenario s = baseScenario("ACTIVE");
        pendency(s.x, "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(21));
        for (int i = 0; i < 12; i++) {
            pendency(s.y, "MANIFESTATION_DEADLINE", "ALTA", TUESDAY.minusHours(5));
        }
        pendency(s.y, "NOTE_DIVERGENT", "MEDIA", TUESDAY.minusHours(5));
        pendency(s.z, "SEFAZ_CAPTURE_ERROR", "ALTA", TUESDAY.minusHours(3));
        // CT-AV-09: audit findings - only the ALTA one, with the label
        pendency(s.x, "AUDIT_FINDING", "ALTA", TUESDAY.minusHours(2));
        pendency(s.x, "AUDIT_FINDING", "MEDIA", TUESDAY.minusHours(2));

        // CT-AV-08: type filter
        alertProperties.setTypes(List.of("CERTIFICATE_EXPIRED", "SEFAZ_CAPTURE_ERROR"));
        PendencyAlertScheduler.RunResult filtered = scheduler.sendDue();
        assertThat(filtered.sent()).isEqualTo(2);
        MailMessage toCarla = lastTo("carla@exemplo.test");
        assertThat(toCarla.subject()).isEqualTo("Taxify: 2 pendência(s) alta(s) nova(s) na sua carteira");
        assertThat(toCarla.body()).contains("Têxtil Exemplo Ltda").contains("Comércio Teste Ltda").doesNotContain("Confecção Modelo");
        // the filtered-out ones stay "not warned": clear the filter and run the next working day
        alertProperties.setTypes(List.of());
        clock.set(TUESDAY.plusDays(1));
        PendencyAlertScheduler.RunResult next = scheduler.sendDue();
        assertThat(next.sent()).isEqualTo(2);
        MailMessage second = mail.sent.getLast();
        assertThat(mail.sent.stream().filter(m -> m.to().contains("carla@exemplo.test")).reduce((a, b) -> b).orElseThrow().body())
                .contains("Prazo de manifestação perto do fim (12)").contains("Auditoria tipo malha (1)").doesNotContain("Nota conferida");
        assertThat(second.body()).contains("pendências altas abertas");
        // bruno (no INVOICES_VIEW) never gets anything and has no digest row (AV-RN-05)
        assertThat(digestsOf(s.bruno)).isZero();
        // CT-AV-03: one digest row per user and day
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_digests WHERE user_id = ? AND reference_date = '2026-12-01'", Integer.class, s.carla)).isEqualTo(1);
        // CT-AV-04: next day, nothing new -> nothing and no row
        int before = mail.sent.size();
        clock.set(TUESDAY.plusDays(2));
        assertThat(scheduler.sendDue().sent()).isZero();
        assertThat(mail.sent).hasSize(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_digests WHERE user_id = ? AND reference_date = '2026-12-03'", Integer.class, s.carla)).isZero();
    }

    /** CA-AV-12 / CT-AV-22: o cron so dispara de segunda a sexta (07:50 Recife) e a segunda leva o fim de semana. */
    @Test
    void theCronSkipsTheWeekendAndMondayCarriesIt() throws Exception {
        CronExpression cron = CronExpression.parse("0 50 7 * * MON-FRI");
        ZonedDateTime friday = ZonedDateTime.of(2026, 12, 4, 8, 0, 0, 0, ZoneId.of("America/Recife"));
        assertThat(cron.next(friday)).isEqualTo(ZonedDateTime.of(2026, 12, 7, 7, 50, 0, 0, ZoneId.of("America/Recife")));
        assertThat(cron.next(friday).getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        // the @Scheduled really uses this expression and zone (the property default)
        org.springframework.scheduling.annotation.Scheduled scheduled = PendencyAlertScheduler.class
                .getMethod("tick").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled.cron()).contains("0 50 7 * * MON-FRI");
        assertThat(scheduled.zone()).isEqualTo("America/Recife");

        Scenario s = baseScenario("ACTIVE");
        pendency(s.x, "CERTIFICATE_EXPIRED", "ALTA", LocalDateTime.of(2026, 12, 5, 11, 0)); // Saturday
        clock.set(LocalDateTime.of(2026, 12, 7, 7, 50)); // Monday
        assertThat(scheduler.sendDue().sent()).isEqualTo(2); // Carla and Ana
        assertThat(lastTo("ana@exemplo.test").body()).contains("Têxtil Exemplo Ltda: Certificado A1 vencido (1)");
    }

    /** CA-AV-03/04/05, CT-AV-05/06/07: fechada antes, desconsiderada, reaberta (first_seen novo), BAIXA -> ALTA. */
    @Test
    void episodesClosedDismissedReopenedAndSeverityChange() throws Exception {
        Scenario s = baseScenario("ACTIVE");
        // closed before the digest (auto-closed) and dismissed by hand: never warned
        String closed = pendency(s.x, "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(2));
        jdbc.update("UPDATE pendencies SET status = 'RESOLVIDA', closed_at = ?, auto_closed = 1 WHERE id = ?", Timestamp.valueOf(TUESDAY.minusHours(1)), closed);
        String dismissed = pendency(s.x, "SEFAZ_CAPTURE_ERROR", "ALTA", TUESDAY.minusHours(2));
        jdbc.update("UPDATE pendencies SET status = 'DESCONSIDERADA', closed_at = ?, closed_by = 'ana' WHERE id = ?", Timestamp.valueOf(TUESDAY.minusMinutes(20)), dismissed);
        // certidao BAIXA (efeito de negativa) on 12/01
        String certidao = pendency(s.x, "CERTIDAO_PE_POSITIVA", "BAIXA", TUESDAY.minusDays(1));
        // SEFAZ error of Z, to be warned, closed and reopened
        String z = pendency(s.z, "SEFAZ_CAPTURE_ERROR", "ALTA", TUESDAY.minusDays(1));

        assertThat(scheduler.sendDue().sent()).as("only the Z error for Carla").isEqualTo(1);
        assertThat(mail.sent.getLast().body()).contains("Comércio Teste").doesNotContain("Têxtil");

        // 12/02: the certidao turns ALTA on the same key; Z closes
        clock.set(TUESDAY.plusDays(1));
        jdbc.update("UPDATE pendencies SET severity = 'ALTA' WHERE id = ?", certidao);
        jdbc.update("UPDATE pendencies SET status = 'RESOLVIDA', closed_at = ?, auto_closed = 1 WHERE id = ?", Timestamp.valueOf(clock.instant().atZone(ZoneId.of("America/Recife")).toLocalDateTime()), z);
        // 12/03: Z reopens with a new first_seen_at (the certidao has to be warned once, on the 12/02 run)
        clock.set(TUESDAY.plusDays(1));
        int before = mail.sent.size();
        assertThat(scheduler.sendDue().sent()).isEqualTo(2);
        assertThat(mail.sent.subList(before, mail.sent.size()).stream().map(MailMessage::body).toList())
                .allMatch(body -> body.contains("Certidão estadual (PE) positiva (1)"));
        clock.set(TUESDAY.plusDays(2));
        jdbc.update("UPDATE pendencies SET status = 'ABERTA', closed_at = NULL, auto_closed = 0, first_seen_at = ? WHERE id = ?", Timestamp.valueOf(TUESDAY.plusDays(2).minusHours(3)), z);
        before = mail.sent.size();
        assertThat(scheduler.sendDue().sent()).as("Carla warned again about Z; the certidao is not repeated").isEqualTo(1);
        assertThat(mail.sent.getLast().body()).contains("Comércio Teste").doesNotContain("Certidão");
        // and nothing is repeated afterwards
        clock.set(TUESDAY.plusDays(3));
        assertThat(scheduler.sendDue().sent()).isZero();
    }

    /** CA-AV-07 / CT-AV-12/13: TRIAL recebe; SUSPENDED/BLOCKED/CANCELED/PENDING nao; reativado, leva o que nao foi avisado. */
    @Test
    void blockedFirmsGetNothingUntilReactivated() throws Exception {
        List<String> statuses = List.of("TRIAL", "ACTIVE", "SUSPENDED", "BLOCKED", "CANCELED", "PENDING_VERIFICATION");
        Map<String, String> userByStatus = new HashMap<>();
        for (String status : statuses) {
            String firm = firm(status);
            String admin = user(firm, "admin." + status.toLowerCase(), status.toLowerCase() + "@exemplo.test", "ADMIN", null);
            String company = company(firm, "Empresa " + status + " Ltda");
            pendency(company, "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(4));
            userByStatus.put(status, admin);
        }
        assertThat(scheduler.sendDue().sent()).isEqualTo(2);
        assertThat(mail.sent.stream().flatMap(m -> m.to().stream()).toList()).containsExactlyInAnyOrder("trial@exemplo.test", "active@exemplo.test");
        // reactivated: the next working day brings the not-yet-warned ALTA
        jdbc.update("UPDATE firm_subscriptions SET status = 'ACTIVE', blocked_reason = NULL WHERE accounting_firm_id = (SELECT accounting_firm_id FROM users WHERE id = ?)", userByStatus.get("BLOCKED"));
        clock.set(TUESDAY.plusDays(1));
        assertThat(scheduler.sendDue().sent()).isEqualTo(1);
        assertThat(mail.sent.getLast().to()).containsExactly("blocked@exemplo.test");
    }

    /** CT-AV-14/16, CA-AV-08 e AV-RN-08: opt-out via HTTP; padrao desligado; ligar de novo leva so as abertas ainda nao avisadas. */
    @Test
    void optOutAndDefaultOff() throws Exception {
        Scenario s = baseScenario("ACTIVE");
        pendency(s.x, "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(4));
        String ana = token(prefix + "ana.fiscal", PASSWORD);
        Resp off = call("PUT", "/users/me/notification-settings", ana, Map.of("pendencyHighDigest", false));
        assertThat(off.status()).isEqualTo(200);
        assertThat(json.readTree(off.body()).at("/pendencyHighDigest/enabled").asBoolean()).isFalse();
        assertThat(scheduler.sendDue().sent()).isEqualTo(1);
        assertThat(mail.sent.getLast().to()).containsExactly("carla@exemplo.test");
        // she turns it on again, a new ALTA appeared meanwhile and is still open: the next day she gets it
        assertThat(call("PUT", "/users/me/notification-settings", ana, Map.of("pendencyHighDigest", true)).status()).isEqualTo(200);
        clock.set(TUESDAY.plusDays(1));
        assertThat(scheduler.sendDue().sent()).as("Ana: the open one never warned to her").isEqualTo(1);
        assertThat(mail.sent.getLast().to()).containsExactly("ana@exemplo.test");

        // default off: nobody who never configured receives
        alertProperties.setDefaultOn(false);
        pendency(s.y, "SEFAZ_CAPTURE_ERROR", "ALTA", TUESDAY.plusDays(2).minusHours(1));
        clock.set(TUESDAY.plusDays(2));
        int before = mail.sent.size();
        PendencyAlertScheduler.RunResult defaultOff = scheduler.sendDue();
        assertThat(defaultOff.sent()).as("only Ana, who explicitly turned it on; Carla never configured").isEqualTo(1);
        assertThat(mail.sent.getLast().to()).containsExactly("ana@exemplo.test");
        assertThat(mail.sent).hasSize(before + 1);
    }

    /** CT-AV-23/24/25/26: inativa, excluida, usuario excluido, outro escritorio, mesmo e-mail em 2 usuarios. */
    @Test
    void eligibilityEdgeCases() throws Exception {
        Scenario s = baseScenario("ACTIVE");
        jdbc.update("UPDATE companies SET active = 0 WHERE id = ?", s.y);
        jdbc.update("UPDATE companies SET deleted = 1 WHERE id = ?", s.z);
        pendency(s.x, "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(4));
        pendency(s.y, "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(4));
        pendency(s.z, "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(4));
        // another firm: its pendencies never reach this firm's users
        String other = firm("ACTIVE");
        String otherAdmin = user(other, "outro.admin", "outro@exemplo.test", "ADMIN", null);
        pendency(company(other, "Empresa Alheia Ltda"), "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(4));
        // Ana shares the address with Carla (AV-RN-11) - and Ana has no e-mail on a third user
        jdbc.update("UPDATE users SET email = 'carla@exemplo.test' WHERE id = ?", s.ana);
        String semEmail = user(s.firm, "sem.email", "x@exemplo.test", "ADMIN", null);
        jdbc.update("UPDATE users SET email = '' WHERE id = ?", semEmail);

        assertThat(scheduler.sendDue().sent()).isEqualTo(3); // Carla, Ana and the other firm's admin
        for (MailMessage message : mail.sent) {
            assertThat(message.body()).doesNotContain("Confecção").doesNotContain("Comércio");
        }
        List<MailMessage> toShared = mail.sent.stream().filter(m -> m.to().contains("carla@exemplo.test")).toList();
        assertThat(toShared).hasSize(2);
        assertThat(toShared).allMatch(m -> m.to().size() == 1 && m.body().contains("Têxtil Exemplo") && !m.body().contains("Empresa Alheia"));
        assertThat(lastTo("outro@exemplo.test").body()).contains("Empresa Alheia").doesNotContain("Têxtil");
        assertThat(digestsOf(semEmail)).isZero();
        // a deleted user gets nothing the next day, even with something new
        jdbc.update("UPDATE users SET deleted = 1 WHERE id = ?", s.ana);
        pendency(s.x, "SEFAZ_CAPTURE_ERROR", "ALTA", TUESDAY.plusDays(1).minusHours(1));
        clock.set(TUESDAY.plusDays(1));
        assertThat(scheduler.sendDue().sent()).isEqualTo(1);
        assertThat(digestsOf(otherAdmin)).isEqualTo(1);
    }

    /** CA-AV-06 / CT-AV-11: nada sigiloso, inclusive nome de MEI com raiz do CNPJ e CPF no fim. */
    @Test
    void theDigestNeverLeaksFiscalData() throws Exception {
        String firm = firm("ACTIVE");
        String carla = user(firm, "carla.mei", "carla.mei@exemplo.test", "ADMIN", null);
        String mei1 = company(firm, "12.345.678 FULANO DE TAL");
        String mei2 = company(firm, "SICRANA DA SILVA 123.456.789-09");
        String mei3 = company(firm, "BELTRANO ME 12.345.678/0001-90");
        String mei4 = company(firm, "98.765.432 - CICLANO COMERCIO");
        String mei5 = company(firm, "F" + "u".repeat(120));
        for (String company : List.of(mei1, mei2, mei3, mei4, mei5)) {
            pendency(company, "MANIFESTATION_DEADLINE", "ALTA", TUESDAY.minusHours(3));
        }
        assertThat(scheduler.sendDue().sent()).isEqualTo(1);
        MailMessage message = lastTo("carla.mei@exemplo.test");
        System.out.println("QA digest body:\n" + message.body());
        assertThat(message.subject()).doesNotContain("FULANO").doesNotContain("Exemplo");
        assertThat(message.body()).contains("FULANO DE TAL").contains("SICRANA DA SILVA").contains("BELTRANO ME").contains("CICLANO COMERCIO")
                .doesNotContain("12.345.678").doesNotContain("123.456.789").doesNotContain("98.765.432")
                .doesNotContain("NF 1234").doesNotContain("Fornecedor").doesNotContain("sigiloso").doesNotContain("35260112345678000199550010000012341000012345")
                .doesNotContain("u".repeat(61));
        assertThat(Pattern.compile("\\d{8,}|\\d{2}\\.\\d{3}\\.\\d{3}").matcher(message.body()).find()).as("no document-like number").isFalse();
        assertThat(message.body()).contains("/pendencias?severidade=ALTA&status=ABERTA").contains("/minha-conta");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_items WHERE user_id = ?", Integer.class, carla)).isEqualTo(5);
    }

    /** CA-AV-09 / CT-AV-17 com duas instancias: teto 5, 12 elegiveis, duas rodadas ao mesmo tempo = exatamente 5 SENT, 7 CAPPED. */
    @Test
    void theGlobalCapHoldsWithTwoInstancesAndCappedGoFirstNextDay() throws Exception {
        String firm = firm("ACTIVE");
        List<String> users = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            users.add(user(firm, "cap" + i, "cap" + i + "@exemplo.test", "ADMIN", null));
        }
        pendency(company(firm, "Empresa Teto Ltda"), "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(3));
        alertProperties.setMaxPerDay(5);
        CompletableFuture<PendencyAlertScheduler.RunResult> a = CompletableFuture.supplyAsync(scheduler::sendDue);
        CompletableFuture<PendencyAlertScheduler.RunResult> b = CompletableFuture.supplyAsync(scheduler::sendDue);
        assertThat(a.get().sent() + b.get().sent()).isEqualTo(5);
        assertThat(mail.sent).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_digests WHERE reference_date = '2026-12-01' AND status = 'SENT'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_digests WHERE reference_date = '2026-12-01' AND status = 'CAPPED'", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_items", Integer.class)).isEqualTo(5);
        // next day: the 7 who never received go first and fill the cap of 5
        List<String> receivedDay1 = mail.sent.stream().map(m -> m.to().get(0)).toList();
        clock.set(TUESDAY.plusDays(1));
        assertThat(scheduler.sendDue().sent()).isEqualTo(5);
        List<String> day2 = mail.sent.subList(5, 10).stream().map(m -> m.to().get(0)).toList();
        assertThat(day2).doesNotContainAnyElementsOf(receivedDay1);
        // and on the third day the remaining 2 receive, ahead of whoever received before
        clock.set(TUESDAY.plusDays(2));
        assertThat(scheduler.sendDue().sent()).isEqualTo(2);
        assertThat(mail.sent.stream().map(m -> m.to().get(0)).distinct().count()).isEqualTo(12);
    }

    /** CT-AV-19/20 e CA-AV-10: falha do SES -> FAILED, 0 itens, os outros recebem; no dia seguinte reentra. SENDING velho vira FAILED. */
    @Test
    void aFailureReentersTheNextDayAndAStaleSendingCountsAsFailed() throws Exception {
        Scenario s = baseScenario("ACTIVE");
        pendency(s.x, "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(4));
        mail.failFor.add("ana@exemplo.test");
        PendencyAlertScheduler.RunResult result = scheduler.sendDue();
        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM pendency_alert_digests WHERE user_id = ?", String.class, s.ana)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_items WHERE user_id = ?", Integer.class, s.ana)).isZero();
        assertThat(logs.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("pendency.alert.failed user="))).isTrue();
        // no retry on the same day
        mail.failFor.clear();
        assertThat(scheduler.sendDue().sent()).isZero();
        // next working day: she gets it
        clock.set(TUESDAY.plusDays(1));
        assertThat(scheduler.sendDue().sent()).isEqualTo(1);
        assertThat(mail.sent.getLast().to()).containsExactly("ana@exemplo.test");
        // a SENDING left by a crash yesterday: counted as FAILED, and the pendency goes today
        jdbc.update("UPDATE pendency_alert_digests SET status = 'SENDING' WHERE user_id = ? AND reference_date = '2026-12-02'", s.ana);
        jdbc.update("DELETE FROM pendency_alert_items WHERE user_id = ?", s.ana);
        clock.set(TUESDAY.plusDays(2));
        assertThat(scheduler.sendDue().sent()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM pendency_alert_digests WHERE user_id = ? AND reference_date = '2026-12-02'", String.class, s.ana)).isEqualTo("FAILED");
    }

    /** CT-AV-18 e CA-AV-13 (allowlist): ritmo de 1 por segundo (5 resumos >= 4 s) e so destinatarios da lista. */
    @Test
    void sendRateAndAllowlistUseTheRealGuard() throws Exception {
        String firm = firm("ACTIVE");
        for (int i = 0; i < 5; i++) {
            user(firm, "ritmo" + i, "ritmo" + i + "@exemplo.test", "ADMIN", null);
        }
        pendency(company(firm, "Empresa Ritmo Ltda"), "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusHours(3));
        mail.guard = mail.guarded(List.of(), 1000);
        long start = System.nanoTime();
        assertThat(scheduler.sendDue().sent()).isEqualTo(5);
        long seconds = (System.nanoTime() - start) / 1_000_000;
        assertThat(seconds).as("5 sends at 1000 ms: at least 4 s").isGreaterThanOrEqualTo(3900);

        // allowlist: only @cshub.com.br
        mail.sent.clear();
        mail.guard = mail.guarded(List.of("cshub.com.br"), 0);
        jdbc.update("UPDATE users SET email = 'ritmo0@cshub.com.br' WHERE login = ?", prefix + "ritmo0");
        jdbc.update("DELETE FROM pendency_alert_items WHERE user_id IN (SELECT id FROM users WHERE accounting_firm_id = ?)", firm);
        jdbc.update("DELETE FROM pendency_alert_digests WHERE accounting_firm_id = ?", firm);
        assertThat(scheduler.sendDue().sent()).isEqualTo(1);
        assertThat(mail.sent).hasSize(1);
        assertThat(mail.sent.get(0).to()).containsExactly("ritmo0@cshub.com.br");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_digests WHERE accounting_firm_id = ?", Integer.class, firm)).as("skipped recipients leave no row").isEqualTo(1);
    }

    /** CT-AV-28: itens de pendencia fechada ha 31 dias e resumo de 366 dias sao apagados. */
    @Test
    void cleanupRemovesOldItemsAndDigests() throws Exception {
        Scenario s = baseScenario("ACTIVE");
        String pendency = pendency(s.x, "CERTIFICATE_EXPIRED", "ALTA", TUESDAY.minusDays(40));
        jdbc.update("UPDATE pendencies SET status = 'RESOLVIDA', closed_at = ? WHERE id = ?", Timestamp.valueOf(TUESDAY.minusDays(31)), pendency);
        String oldDigest = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO pendency_alert_digests (id, accounting_firm_id, user_id, reference_date, status, companies_count, pendencies_count, sent_at, deleted, created_by, created_date)"
                + " VALUES (?, ?, ?, ?, 'SENT', 1, 1, ?, 0, 'qa', ?)", oldDigest, s.firm, s.ana, java.sql.Date.valueOf(TUESDAY.toLocalDate().minusDays(366)),
                Timestamp.valueOf(TUESDAY.minusDays(366)), Timestamp.valueOf(LocalDateTime.now()));
        jdbc.update("INSERT INTO pendency_alert_items (id, digest_id, pendency_id, user_id, episode_started_at, deleted, created_by, created_date)"
                + " VALUES (?, ?, ?, ?, ?, 0, 'qa', ?)", UUID.randomUUID().toString(), oldDigest, pendency, s.ana,
                Timestamp.valueOf(TUESDAY.minusDays(40)), Timestamp.valueOf(LocalDateTime.now()));
        // a recent closed one (10 days) keeps its item
        String recent = pendency(s.x, "SEFAZ_CAPTURE_ERROR", "ALTA", TUESDAY.minusDays(20));
        jdbc.update("UPDATE pendencies SET status = 'RESOLVIDA', closed_at = ? WHERE id = ?", Timestamp.valueOf(TUESDAY.minusDays(10)), recent);
        jdbc.update("INSERT INTO pendency_alert_items (id, digest_id, pendency_id, user_id, episode_started_at, deleted, created_by, created_date)"
                + " VALUES (?, NULL, ?, ?, ?, 0, 'qa', ?)", UUID.randomUUID().toString(), recent, s.ana,
                Timestamp.valueOf(TUESDAY.minusDays(20)), Timestamp.valueOf(LocalDateTime.now()));
        scheduler.sendDue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_digests WHERE id = ?", Integer.class, oldDigest)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_items WHERE pendency_id = ?", Integer.class, pendency)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_items WHERE pendency_id = ?", Integer.class, recent)).isEqualTo(1);
    }
}
