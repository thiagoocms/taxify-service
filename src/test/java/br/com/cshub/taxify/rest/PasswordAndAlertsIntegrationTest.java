package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import br.com.cshub.taxify.service.pendency.alert.PendencyAlertScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Spec 2026-11-27 de ponta a ponta pelos endpoints: "Esqueci a senha", troca de senha, revogação de sessões pelo claim
 * {@code sv} (inclusive o token antigo sem o claim e o usuário excluído), troca pelo ADMIN, preferências de aviso e o
 * resumo diário de pendências ALTA. Dados fictícios criados a cada execução e apagados no fim; e-mail pela
 * implementação falsa (nada é enviado).
 *
 * <p><b>Só roda contra um MySQL local</b> ({@code DB_HOST=localhost}); cada teste confere a URL real da conexão.
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false", "taxify.saas.access-enabled=false",
        "taxify.password.reset-enabled=true", "taxify.password.reset-purge-enabled=false",
        // the digest is run by the test itself (sendDue), never by its cron
        "taxify.pendency-alerts.enabled=false",
        "taxify.mail.send-interval-ms=0",
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false"
})
@AutoConfigureMockMvc
class PasswordAndAlertsIntegrationTest {

    private static final String BASE = "/api/v1";
    private static final String PASSWORD = "SenhaDeTeste2026";
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");
    private static final Random RANDOM = new Random();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 1, 7, 50);

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock passwordTestClock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        RecordingMailSender passwordRecordingMailSender() {
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
    private JwtEncoder jwtEncoder;
    @Autowired
    private PasswordProperties passwordProperties;
    @Autowired
    private PendencyAlertScheduler alertScheduler;

    private final List<String> firms = new CopyOnWriteArrayList<>();
    private final String prefix = "it" + Integer.toHexString(RANDOM.nextInt(0xfffff)) + ".";

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL");
        clock.set(NOW);
        mail.sent.clear();
        passwordProperties.setResetEnabled(true);
    }

    @AfterEach
    void tearDown() {
        passwordProperties.setResetEnabled(true);
        passwordProperties.setResetPerIpPerDay(10);
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
                jdbc.update("DELETE FROM pendency_alert_items WHERE pendency_id IN (SELECT id FROM pendencies WHERE company_id = ?)",
                        company.get("id"));
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
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'it', ?)",
                id, now, "Escritório Exemplo " + prefix);
        jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, migrated, source, deleted, created_by, created_date)"
                + " VALUES (?, ?, ?, 0, 'MANUAL', 0, 'it', ?)", UUID.randomUUID().toString(), id, status, now);
        firms.add(id);
        return id;
    }

    private String user(String firm, String login, String email, String profile, String accessProfile) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', ?, 'CPF')",
                document, now, digits(11));
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login,"
                        + " name, email, password) VALUES (?, 0, ?, 'it', ?, ?, ?, ?, ?, ?, ?)",
                id, now, profile, firm, document, prefix + login, "Pessoa " + login + " Fictícia", email,
                passwordEncoder.encode(PASSWORD));
        if (accessProfile != null) {
            jdbc.update("INSERT INTO user_access_profiles (user_id, access_profile) VALUES (?, ?)", id, accessProfile);
        }
        return id;
    }

    private String company(String firm, String name) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', ?, 'CNPJ')",
                document, now, digits(14));
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'it', ?, ?, ?, 1)", id, now, firm, document, name);
        return id;
    }

    private void portfolio(String user, String company) {
        jdbc.update("INSERT INTO users_companies (user_id, company_id, role, deleted, created_date, created_by)"
                + " VALUES (?, ?, 'MEMBER', 0, ?, 'it')", user, company, Timestamp.valueOf(LocalDateTime.now()));
    }

    private String pendency(String company, String type, String severity, LocalDateTime firstSeen) {
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO pendencies (id, company_id, type, severity, natural_key, title, detail, status, first_seen_at,"
                        + " last_seen_at, auto_closed, deleted, created_by, created_date)"
                        + " VALUES (?, ?, ?, ?, ?, 'NF 1234 de Fornecedor Fictício', 'detalhe sigiloso', 'ABERTA', ?, ?, 0, 0, 'it', ?)",
                id, company, type, severity, type + ":" + id, Timestamp.valueOf(firstSeen), Timestamp.valueOf(firstSeen), now);
        return id;
    }

    private static String digits(int length) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length; i++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }

    // ------------------------------------------------------------------ HTTP helpers

    private MvcResult login(String login, String password) throws Exception {
        String auth = Base64.getEncoder().encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        return mvc.perform(post(BASE + "/auth").contentType(MediaType.APPLICATION_JSON).content("{\"auth\":\"" + auth + "\"}"))
                .andReturn();
    }

    private String token(String login, String password) throws Exception {
        MvcResult result = login(login, password);
        assertThat(result.getResponse().getStatus()).as("login").isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    private MvcResult json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }

    private String code(MvcResult result) throws Exception {
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        return body.has("code") ? body.get("code").asText() : null;
    }

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private int whoAmI(String token) throws Exception {
        return status(call(auth(get(BASE + "/firm-access"), token)));
    }

    private MailMessage awaitMail(int count) throws InterruptedException {
        for (int i = 0; i < 100 && mail.sent.size() < count; i++) {
            Thread.sleep(50);
        }
        assertThat(mail.sent).hasSizeGreaterThanOrEqualTo(count);
        return mail.sent.get(count - 1);
    }

    /** The password notices go out after the response (PasswordAsyncConfig): wait for the one that matches. */
    private MailMessage awaitMail(java.util.function.Predicate<MailMessage> matching) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            java.util.Optional<MailMessage> found = mail.sent.stream().filter(matching).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("e-mail not sent");
    }

    private static String tokenOf(MailMessage message) {
        Matcher matcher = TOKEN.matcher(message.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    // ------------------------------------------------------------------ sessions (SN-RN-11) and own change (SN-RN-09)

    /** CA-SN-12 / CT-SN-21 / CA-SN-13 / CT-SN-15: two sessions; a change in A keeps A (new token) and drops B. */
    @Test
    void changingTheOwnPasswordKeepsThisSessionAndDropsTheOthers() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "ana.fiscal", "ana@exemplo.test", "EMPLOYEE", "FISCAL");
        String a = token(prefix + "ana.fiscal", PASSWORD);
        String b = token(prefix + "ana.fiscal", PASSWORD);
        assertThat(whoAmI(a)).isEqualTo(200);
        assertThat(whoAmI(b)).isEqualTo(200);

        MvcResult wrong = json(auth(put(BASE + "/users/me/password"), a), Map.of("currentPassword", "errada", "newPassword", "NovaSenha#2026"));
        assertThat(status(wrong)).isEqualTo(400);
        assertThat(code(wrong)).isEqualTo("error.user.password.current.invalid");
        assertThat(whoAmI(a)).as("a wrong current password does not log out").isEqualTo(200);

        MvcResult shortOne = json(auth(put(BASE + "/users/me/password"), a), Map.of("currentPassword", PASSWORD, "newPassword", "Abc1234"));
        assertThat(code(shortOne)).isEqualTo("error.user.password.too.short");

        MvcResult changed = json(auth(put(BASE + "/users/me/password"), a), Map.of("currentPassword", PASSWORD, "newPassword", "NovaSenha#2026"));
        assertThat(status(changed)).isEqualTo(200);
        String fresh = json.readTree(changed.getResponse().getContentAsString()).get("token").asText();

        assertThat(whoAmI(fresh)).isEqualTo(200);
        MvcResult dropped = call(auth(get(BASE + "/firm-access"), b));
        assertThat(status(dropped)).isEqualTo(401);
        assertThat(code(dropped)).isEqualTo("error.auth.session.expired");
        assertThat(whoAmI(a)).isEqualTo(401);

        assertThat(status(login(prefix + "ana.fiscal", PASSWORD))).isEqualTo(403);
        assertThat(status(login(prefix + "ana.fiscal", "NovaSenha#2026"))).isEqualTo(200);
        awaitMail(m -> m.body().contains("por você em Minha conta"));
    }

    /** CA-SN-18 / CT-SN-29: a token issued before the deploy (no "sv") passes while the version is 0, then falls. */
    @Test
    void aTokenWithoutTheVersionClaimPassesUntilTheFirstChange() throws Exception {
        String firm = firm("ACTIVE");
        String ana = user(firm, "ana.antiga", "ana@exemplo.test", "EMPLOYEE", "FISCAL");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("taxify-service").issuedAt(now).expiresAt(now.plusSeconds(3600))
                .subject(prefix + "ana.antiga").claim("authorities", List.of("EMPLOYEE", "INVOICES_VIEW", "COMPANIES_VIEW"))
                .claim("userId", ana).claim("profile", "EMPLOYEE").claim("tenantId", firm).build();
        String old = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        assertThat(whoAmI(old)).isEqualTo(200);
        jdbc.update("UPDATE users SET session_version = 1 WHERE id = ?", ana);
        assertThat(whoAmI(old)).isEqualTo(401);
    }

    /** CA-SN-19 / CT-SN-30: the ADMIN deletes a user - his open session gets 401 right away (R-02 closed). */
    @Test
    void aDeletedUsersSessionGets401() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "carla.admin", "carla@exemplo.test", "ADMIN", null);
        String bruno = user(firm, "bruno.dp", "bruno@exemplo.test", "EMPLOYEE", "FISCAL");
        String admin = token(prefix + "carla.admin", PASSWORD);
        String session = token(prefix + "bruno.dp", PASSWORD);
        assertThat(whoAmI(session)).isEqualTo(200);

        assertThat(status(call(auth(delete(BASE + "/users/" + bruno), admin)))).isIn(200, 204);

        MvcResult result = call(auth(get(BASE + "/companies"), session));
        assertThat(status(result)).isEqualTo(401);
        assertThat(code(result)).isEqualTo("error.auth.session.expired");
    }

    /** CA-SN-14 / CT-SN-23: a SUSPENDED firm changes its own password (not 403); writes stay blocked. */
    @Test
    void aSuspendedFirmCanChangeItsOwnPassword() throws Exception {
        String firm = firm("SUSPENDED");
        user(firm, "ana.suspensa", "ana@exemplo.test", "EMPLOYEE", "FISCAL");
        String session = token(prefix + "ana.suspensa", PASSWORD);
        MvcResult changed = json(auth(put(BASE + "/users/me/password"), session),
                Map.of("currentPassword", PASSWORD, "newPassword", "NovaSenha#2026"));
        assertThat(status(changed)).isEqualTo(200);
        String fresh = json.readTree(changed.getResponse().getContentAsString()).get("token").asText();
        MvcResult settings = json(auth(put(BASE + "/users/me/notification-settings"), fresh), Map.of("pendencyHighDigest", false));
        assertThat(status(settings)).isEqualTo(200);
    }

    /** CT-SN-22: 5 wrong, the 6th (right) is 429; after the window, 200. */
    @Test
    void theFailureLimitSurvivesInTheDatabase() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "ana.limite", "ana@exemplo.test", "EMPLOYEE", "FISCAL");
        String session = token(prefix + "ana.limite", PASSWORD);
        for (int i = 0; i < 5; i++) {
            clock.set(NOW.plusMinutes(i));
            assertThat(status(json(auth(put(BASE + "/users/me/password"), session),
                    Map.of("currentPassword", "errada", "newPassword", "NovaSenha#2026")))).isEqualTo(400);
        }
        clock.set(NOW.plusMinutes(6));
        MvcResult limited = json(auth(put(BASE + "/users/me/password"), session),
                Map.of("currentPassword", PASSWORD, "newPassword", "NovaSenha#2026"));
        assertThat(status(limited)).isEqualTo(429);
        assertThat(code(limited)).isEqualTo("error.user.password.change.rate.limited");
        clock.set(NOW.plusMinutes(61));
        assertThat(status(json(auth(put(BASE + "/users/me/password"), session),
                Map.of("currentPassword", PASSWORD, "newPassword", "NovaSenha#2026")))).isEqualTo(200);
    }

    // ------------------------------------------------------------------ forgot / reset (SN-RN-01 to SN-RN-06)

    /** CA-SN-01 / CA-SN-05 / CA-SN-07 / CA-SN-08 / CT-SN-10: the whole e-mail flow. */
    @Test
    void forgotAndResetByEmail() throws Exception {
        String firm = firm("ACTIVE");
        String ana = user(firm, "ana.reset", "ana.reset@exemplo.test", "EMPLOYEE", "FISCAL");
        String open = token(prefix + "ana.reset", PASSWORD);

        JsonNode config = json.readTree(call(get(BASE + "/auth/password/config")).getResponse().getContentAsString());
        assertThat(config.get("available").asBoolean()).isTrue();
        assertThat(config.get("resetMinutes").asInt()).isEqualTo(60);
        assertThat(config.get("minLength").asInt()).isEqualTo(8);

        MvcResult forgot = json(post(BASE + "/auth/password/forgot"), Map.of("identifier", prefix + "ana.reset"));
        assertThat(status(forgot)).isEqualTo(202);
        assertThat(forgot.getResponse().getContentAsString()).isEmpty();
        MailMessage message = awaitMail(1);
        assertThat(message.to()).containsExactly("ana.reset@exemplo.test");
        assertThat(message.body()).contains("/auth/redefinir-senha#token=");
        String token = tokenOf(message);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? AND token_hash <> ?",
                Integer.class, ana, token)).isEqualTo(1);

        assertThat(status(json(post(BASE + "/auth/password/reset/check"), Map.of("token", token)))).isEqualTo(204);
        MvcResult shortOne = json(post(BASE + "/auth/password/reset"), Map.of("token", token, "newPassword", "Abc1234"));
        assertThat(code(shortOne)).isEqualTo("error.user.password.too.short");
        assertThat(status(json(post(BASE + "/auth/password/reset/check"), Map.of("token", token)))).as("not consumed").isEqualTo(204);

        // two clicks at the same time: one 204, the other 400
        CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(() -> resetQuietly(token));
        CompletableFuture<MvcResult> second = CompletableFuture.supplyAsync(() -> resetQuietly(token));
        List<Integer> statuses = new ArrayList<>(List.of(status(first.get()), status(second.get())));
        assertThat(statuses).containsExactlyInAnyOrder(204, 400);

        MvcResult again = json(post(BASE + "/auth/password/reset"), Map.of("token", token, "newPassword", "Abc12345"));
        assertThat(code(again)).isEqualTo("error.password.reset.token.invalid");
        MvcResult expired = call(auth(get(BASE + "/firm-access"), open));
        assertThat(code(expired)).isEqualTo("error.auth.session.expired");
        assertThat(status(login(prefix + "ana.reset", "Abc12345"))).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ? AND requested_ip IS NOT NULL",
                Integer.class, ana)).isZero();
        awaitMail(m -> m.body().contains("por você pela redefinição por e-mail"));
    }

    private MvcResult resetQuietly(String token) {
        try {
            return json(post(BASE + "/auth/password/reset"), Map.of("token", token, "newPassword", "Abc12345"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** CA-SN-02 / CT-SN-03 / CA-SN-10: unknown, empty and unverified - 202 and nothing. An old token is ignored. */
    @Test
    void forgotNeverRevealsAnything() throws Exception {
        String pending = firm("PENDING_VERIFICATION");
        String owner = user(pending, "dono.pendente", "dono@exemplo.test", "ADMIN", null);
        assertThat(status(json(post(BASE + "/auth/password/forgot"), Map.of("identifier", prefix + "nao.existe")))).isEqualTo(202);
        assertThat(status(json(post(BASE + "/auth/password/forgot"), Map.of()))).isEqualTo(202);
        assertThat(status(json(post(BASE + "/auth/password/forgot"), Map.of("identifier", "   ")))).isEqualTo(202);
        assertThat(status(call(post(BASE + "/auth/password/forgot")))).isEqualTo(202);
        assertThat(status(json(auth(post(BASE + "/auth/password/forgot"), "token.velho.invalido"),
                Map.of("identifier", prefix + "dono.pendente")))).isIn(202, 401);
        assertThat(status(json(post(BASE + "/auth/password/forgot"), Map.of("identifier", prefix + "dono.pendente")))).isEqualTo(202);
        Thread.sleep(500);
        assertThat(mail.sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE user_id = ?", Integer.class, owner)).isZero();
        assertThat(code(json(post(BASE + "/auth/password/reset/check"), Map.of("token", "desconhecido"))))
                .isEqualTo("error.password.reset.token.invalid");
    }

    /** CA-SN-20 / CT-SN-32: switched off. */
    @Test
    void switchedOff() throws Exception {
        passwordProperties.setResetEnabled(false);
        JsonNode config = json.readTree(call(get(BASE + "/auth/password/config")).getResponse().getContentAsString());
        assertThat(config.get("available").asBoolean()).isFalse();
        MvcResult forgot = json(post(BASE + "/auth/password/forgot"), Map.of("identifier", "qualquer"));
        assertThat(status(forgot)).isEqualTo(503);
        assertThat(code(forgot)).isEqualTo("error.password.reset.unavailable");
        assertThat(status(json(post(BASE + "/auth/password/reset/check"), Map.of("token", "x")))).isEqualTo(503);
    }

    // ------------------------------------------------------------------ ADMIN (SN-RN-10) and ":" (SN-RN-08)

    /** CA-SN-15 / CA-SN-16 / CT-SN-24 / CT-SN-26 / CA-SN-17. */
    @Test
    void theAdminSetsATeammatesPasswordButNotHisOwn() throws Exception {
        String firm = firm("ACTIVE");
        String carla = user(firm, "carla.adm", "carla@exemplo.test", "ADMIN", null);
        String bruno = user(firm, "bruno.colab", "bruno@exemplo.test", "EMPLOYEE", "FISCAL");
        String admin = token(prefix + "carla.adm", PASSWORD);
        String brunoSession = token(prefix + "bruno.colab", PASSWORD);

        Map<String, Object> brunoBody = userBody(bruno, "bruno.colab", "bruno@exemplo.test", "EMPLOYEE", "Senha2026x");
        MvcResult updated = json(auth(put(BASE + "/users/" + bruno), admin), brunoBody);
        assertThat(status(updated)).as(updated.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(whoAmI(brunoSession)).isEqualTo(401);
        MailMessage notice = awaitMail(m -> m.body().contains("pelo administrador do escritório"));
        assertThat(notice.to()).containsExactly("bruno@exemplo.test");
        assertThat(status(login(prefix + "bruno.colab", "Senha2026x"))).isEqualTo(200);

        MvcResult tooShort = json(auth(put(BASE + "/users/" + bruno), admin),
                userBody(bruno, "bruno.colab", "bruno@exemplo.test", "EMPLOYEE", "abc"));
        assertThat(code(tooShort)).isEqualTo("error.user.password.too.short");

        MvcResult own = json(auth(put(BASE + "/users/" + carla), admin),
                userBody(carla, "carla.adm", "carla@exemplo.test", "ADMIN", "OutraSenha2026"));
        assertThat(status(own)).isEqualTo(400);
        assertThat(code(own)).isEqualTo("error.user.password.use.self.change");
        assertThat(status(json(auth(put(BASE + "/users/" + carla), admin),
                userBody(carla, "carla.adm", "carla@exemplo.test", "ADMIN", "")))).isEqualTo(200);
        assertThat(whoAmI(admin)).as("editing his own record without a password keeps his session").isEqualTo(200);

        // CA-SN-17: a password with ":" logs in
        jdbc.update("UPDATE users SET password = ? WHERE id = ?", passwordEncoder.encode("abc:12345"), bruno);
        assertThat(status(login(prefix + "bruno.colab", "abc:12345"))).isEqualTo(200);
    }

    private Map<String, Object> userBody(String id, String login, String email, String profile, String password) {
        String document = jdbc.queryForObject("SELECT d.number FROM users u JOIN documents d ON d.id = u.document_id WHERE u.id = ?",
                String.class, id);
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("id", id);
        body.put("name", "Pessoa Fictícia");
        body.put("documentNumber", document);
        body.put("documentType", "CPF");
        body.put("login", prefix + login);
        body.put("password", password);
        body.put("email", email);
        body.put("profile", profile);
        body.put("accessProfiles", "ADMIN".equals(profile) ? List.of() : List.of("FISCAL"));
        return body;
    }

    // ------------------------------------------------------------------ notification settings and the digest (section 6)

    /** AV-RN-08: default on, eligible by INVOICES_VIEW; PUT stores it; a missing value is 400. */
    @Test
    void notificationSettings() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "ana.avisos", "ana@exemplo.test", "EMPLOYEE", "FISCAL");
        user(firm, "bruno.folha", "bruno@exemplo.test", "EMPLOYEE", "PAYROLL");
        String ana = token(prefix + "ana.avisos", PASSWORD);
        String bruno = token(prefix + "bruno.folha", PASSWORD);

        JsonNode initial = json.readTree(call(auth(get(BASE + "/users/me/notification-settings"), ana)).getResponse().getContentAsString());
        assertThat(initial.at("/pendencyHighDigest/enabled").asBoolean()).isTrue();
        assertThat(initial.at("/pendencyHighDigest/eligible").asBoolean()).isTrue();
        JsonNode brunos = json.readTree(call(auth(get(BASE + "/users/me/notification-settings"), bruno)).getResponse().getContentAsString());
        assertThat(brunos.at("/pendencyHighDigest/eligible").asBoolean()).isFalse();

        MvcResult off = json(auth(put(BASE + "/users/me/notification-settings"), ana), Map.of("pendencyHighDigest", false));
        assertThat(json.readTree(off.getResponse().getContentAsString()).at("/pendencyHighDigest/enabled").asBoolean()).isFalse();
        JsonNode after = json.readTree(call(auth(get(BASE + "/users/me/notification-settings"), ana)).getResponse().getContentAsString());
        assertThat(after.at("/pendencyHighDigest/enabled").asBoolean()).isFalse();
        MvcResult invalid = json(auth(put(BASE + "/users/me/notification-settings"), ana), Map.of());
        assertThat(code(invalid)).isEqualTo("error.notification.settings.invalid");
    }

    /** CA-AV-01 / CA-AV-02 / CA-AV-06 / CA-AV-08 / CA-AV-11 / CT-AV-25 with the real queries. */
    @Test
    void theDailyDigestEndToEnd() throws Exception {
        String firm = firm("ACTIVE");
        String carla = user(firm, "carla.resumo", "carla@exemplo.test", "ADMIN", null);
        String ana = user(firm, "ana.resumo", "ana@exemplo.test", "EMPLOYEE", "FISCAL");
        String bruno = user(firm, "bruno.resumo", "bruno@exemplo.test", "EMPLOYEE", "PAYROLL");
        String x = company(firm, "Têxtil Exemplo Ltda");
        String y = company(firm, "Confecção Modelo ME");
        String z = company(firm, "Comércio Teste Ltda");
        portfolio(ana, x);
        portfolio(ana, y);
        portfolio(bruno, z);
        pendency(x, "CERTIFICATE_EXPIRED", "ALTA", NOW.minusHours(21));
        for (int i = 0; i < 12; i++) {
            pendency(y, "MANIFESTATION_DEADLINE", "ALTA", NOW.minusHours(5));
        }
        pendency(y, "NOTE_DIVERGENT", "MEDIA", NOW.minusHours(5));
        pendency(z, "SEFAZ_CAPTURE_ERROR", "ALTA", NOW.minusHours(3));
        // another firm, blocked: never mixed in, never sent
        String other = firm("SUSPENDED");
        String otherAdmin = user(other, "outro.admin", "outro@exemplo.test", "ADMIN", null);
        pendency(company(other, "Outra Empresa Ltda"), "CERTIFICATE_EXPIRED", "ALTA", NOW.minusHours(3));

        // two instances at the same time: one e-mail per user
        CompletableFuture<PendencyAlertScheduler.RunResult> first = CompletableFuture.supplyAsync(alertScheduler::sendDue);
        CompletableFuture<PendencyAlertScheduler.RunResult> second = CompletableFuture.supplyAsync(alertScheduler::sendDue);
        assertThat(first.get().sent() + second.get().sent()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT IS_FREE_LOCK('taxify:pendency-alert-budget')", Integer.class)).isEqualTo(1);

        assertThat(mail.sent).hasSize(2);
        MailMessage toCarla = mail.sent.stream().filter(m -> m.to().contains("carla@exemplo.test")).findFirst().orElseThrow();
        MailMessage toAna = mail.sent.stream().filter(m -> m.to().contains("ana@exemplo.test")).findFirst().orElseThrow();
        assertThat(toCarla.subject()).isEqualTo("Taxify: 14 pendência(s) alta(s) nova(s) na sua carteira");
        assertThat(toAna.subject()).isEqualTo("Taxify: 13 pendência(s) alta(s) nova(s) na sua carteira");
        assertThat(toCarla.body()).contains("Comércio Teste Ltda: Captura SEFAZ com erro (1)")
                .contains("Confecção Modelo ME: Prazo de manifestação perto do fim (12)")
                .contains("Têxtil Exemplo Ltda: Certificado A1 vencido (1)")
                .doesNotContain("Nota conferida").doesNotContain("1234").doesNotContain("Fornecedor").doesNotContain("Outra Empresa");
        assertThat(toAna.body()).doesNotContain("Comércio Teste");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_items WHERE user_id = ?", Integer.class, carla)).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT status FROM pendency_alert_digests WHERE user_id = ?", String.class, ana)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pendency_alert_digests WHERE user_id IN (?, ?)", Integer.class,
                bruno, otherAdmin)).isZero();

        // CA-AV-02: the same day again, and the next day with nothing new - nothing
        alertScheduler.sendDue();
        clock.set(NOW.plusDays(1));
        alertScheduler.sendDue();
        assertThat(mail.sent).hasSize(2);

        // a new ALTA the next day goes; Ana switched off (CA-AV-08)
        jdbc.update("INSERT INTO user_notification_settings (id, user_id, type, enabled, deleted, created_by, created_date)"
                + " VALUES (?, ?, 'PENDENCY_HIGH_DIGEST', 0, 0, 'it', ?)", UUID.randomUUID().toString(), ana, Timestamp.valueOf(LocalDateTime.now()));
        pendency(x, "CERTIDAO_PE_VENCIDA", "ALTA", NOW.plusDays(1).minusHours(1));
        clock.set(NOW.plusDays(2));
        alertScheduler.sendDue();
        assertThat(mail.sent).hasSize(3);
        assertThat(mail.sent.getLast().to()).containsExactly("carla@exemplo.test");
        assertThat(mail.sent.getLast().body()).contains("Certidão estadual (PE) vencida (1)").contains("15 pendências altas abertas");
    }

    // ------------------------------------------------------------------ review items 1 and 7

    /**
     * Review item 1 (CT-SN-08 with concurrency): 10 accounts asking at the same time from the same IP, cap 3 - exactly
     * 3 links, and the MySQL named lock is free afterwards (released on the transaction's own connection).
     */
    @Test
    void theIpCapHoldsUnderConcurrency() throws Exception {
        passwordProperties.setResetPerIpPerDay(3);
        String firm = firm("ACTIVE");
        List<String> logins = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            user(firm, "conta.ip" + i, "conta" + i + "@exemplo.test", "EMPLOYEE", "FISCAL");
            logins.add(prefix + "conta.ip" + i);
        }
        String ip = "198.51.100." + (1 + RANDOM.nextInt(250));
        List<CompletableFuture<MvcResult>> calls = new ArrayList<>();
        for (String login : logins) {
            calls.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return json(post(BASE + "/auth/password/forgot").header("X-Forwarded-For", ip), Map.of("identifier", login));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        for (CompletableFuture<MvcResult> call : calls) {
            assertThat(status(call.get())).isEqualTo(202);
        }
        awaitMail(3);
        Thread.sleep(1500);
        assertThat(mail.sent).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens WHERE requested_ip = ?", Integer.class, ip))
                .isEqualTo(3);
        String lock = br.com.cshub.taxify.common.NamedLocks.name("pwreset:", ip);
        assertThat(jdbc.queryForObject("SELECT IS_FREE_LOCK(?)", Integer.class, lock)).isEqualTo(1);
    }

    /** Review item 7: the ADMIN cannot change his own login (the token carries it); he can change a teammate's. */
    @Test
    void theAdminCannotChangeHisOwnLogin() throws Exception {
        String firm = firm("ACTIVE");
        String carla = user(firm, "carla.login", "carla@exemplo.test", "ADMIN", null);
        String bruno = user(firm, "bruno.login", "bruno@exemplo.test", "EMPLOYEE", "FISCAL");
        String admin = token(prefix + "carla.login", PASSWORD);

        MvcResult own = json(auth(put(BASE + "/users/" + carla), admin),
                userBody(carla, "carla.outro", "carla@exemplo.test", "ADMIN", ""));
        assertThat(status(own)).isEqualTo(400);
        assertThat(code(own)).isEqualTo("error.user.login.self.change");
        assertThat(whoAmI(admin)).isEqualTo(200);

        assertThat(status(json(auth(put(BASE + "/users/" + bruno), admin),
                userBody(bruno, "bruno.outro", "bruno@exemplo.test", "EMPLOYEE", "")))).isEqualTo(200);
    }

    /** Review item 6: the ADMIN's change invalidates the teammate's open reset links. */
    @Test
    void theAdminsChangeInvalidatesTheOpenLinks() throws Exception {
        String firm = firm("ACTIVE");
        user(firm, "carla.links", "carla@exemplo.test", "ADMIN", null);
        String bruno = user(firm, "bruno.links", "bruno.links@exemplo.test", "EMPLOYEE", "FISCAL");
        String admin = token(prefix + "carla.links", PASSWORD);
        json(post(BASE + "/auth/password/forgot"), Map.of("identifier", prefix + "bruno.links"));
        String link = tokenOf(awaitMail(m -> m.to().contains("bruno.links@exemplo.test") && m.body().contains("#token=")));

        assertThat(status(json(auth(put(BASE + "/users/" + bruno), admin),
                userBody(bruno, "bruno.links", "bruno.links@exemplo.test", "EMPLOYEE", "Senha2026x")))).isEqualTo(200);
        assertThat(code(json(post(BASE + "/auth/password/reset/check"), Map.of("token", link))))
                .isEqualTo("error.password.reset.token.invalid");
    }
}
