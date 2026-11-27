package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Taxify SaaS fase 1 de ponta a ponta pelos endpoints. Dados fictícios gerados a cada execução; e-mail pela
 * implementação falsa (nada é enviado). Cobre CA1-01, CA1-02, CA1-03, CA1-06, CA1-08, CA1-09, CA1-15, CA1-17, CA1-20
 * e CA1-21.
 *
 * <p><b>Só roda contra um MySQL local:</b> sem a variável de ambiente {@code DB_HOST=localhost} (ou 127.0.0.1) a classe
 * é desligada antes de subir o contexto - nenhuma migration, nenhum dado. O {@code application-default.yaml} pode
 * apontar para a EC2, e esse valor nunca liga o teste. Além disso, cada teste confere a URL real da conexão e apaga no
 * {@code @AfterEach} tudo o que criou (escritórios do cadastro, o escritório e o admin da plataforma, usuários,
 * documentos, aceites, links, assinaturas e eventos).
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        // the daily purge and trial notices never run on their own against the shared test database
        "taxify.saas.purge-enabled=false", "taxify.saas.trial-notice-enabled=false",
        "taxify.saas.signup-enabled=true",
        "taxify.saas.terms-version=IT-2026-11",
        "taxify.platform.admin-logins=it.csadmin.f1",
        "taxify.platform.firm-id=" + FirmAccessFlowIntegrationTest.PLATFORM_FIRM,
        // no scheduler talks to the outside world during the test
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false"
})
@AutoConfigureMockMvc
class FirmAccessFlowIntegrationTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-00000000cf01";
    private static final String PLATFORM_LOGIN = "it.csadmin.f1";
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
    private br.com.cshub.taxify.config.SaasProperties properties;
    @Autowired
    private org.springframework.context.MessageSource messages;
    @Autowired
    private MutableClock clock;
    @Autowired
    private RecordingMailSender mail;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** CNPJs dos escritórios que este teste cadastrou - apagados no fim. */
    private final List<String> createdCnpjs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        String url = jdbc.queryForObject("SELECT CONCAT(@@hostname, '')", String.class);
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        org.junit.jupiter.api.Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + url);
        clock.set(LocalDateTime.of(2026, 11, 26, 10, 0));
        mail.sent.clear();
        cleanUp();
        createPlatformAdmin();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    /** O escritório da CS Hub e o seu admin, criados direto no banco para este teste. */
    private void createPlatformAdmin() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'it', ?)",
                PLATFORM_FIRM, now, "CS Hub (teste)");
        String documentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', ?, 'CPF')",
                documentId, now, randomDigits(11));
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login,"
                        + " name, email, password) VALUES (?, 0, ?, 'it', 'ADMIN', ?, ?, ?, 'Admin Plataforma Teste', 'admin@exemplo.test', ?)",
                UUID.randomUUID().toString(), now, PLATFORM_FIRM, documentId, PLATFORM_LOGIN, passwordEncoder.encode(PASSWORD));
    }

    /** Apaga os escritórios do teste (o da plataforma e os cadastrados) com tudo o que pende deles. */
    private void cleanUp() {
        List<String> firms = new java.util.ArrayList<>(List.of(PLATFORM_FIRM));
        for (String cnpj : createdCnpjs) {
            firms.addAll(jdbc.queryForList("SELECT id FROM accounting_firms WHERE document_number = ?", String.class, cnpj));
        }
        // a platform admin left behind by an older version of this test
        firms.addAll(jdbc.queryForList("SELECT accounting_firm_id FROM users WHERE login = ?", String.class, PLATFORM_LOGIN));
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
            jdbc.update("DELETE FROM recovery_tasks WHERE accounting_firm_id = ?", firm);
            List<Map<String, Object>> companies = jdbc.queryForList("SELECT id, document_id FROM companies WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> company : companies) {
                jdbc.update("DELETE FROM subscriptions WHERE company_id = ?", company.get("id"));
                jdbc.update("DELETE FROM company_tax_regimes WHERE company_id = ?", company.get("id"));
                jdbc.update("DELETE FROM users_companies WHERE company_id = ?", company.get("id"));
            }
            jdbc.update("DELETE FROM companies WHERE accounting_firm_id = ?", firm);
            for (String plan : jdbc.queryForList("SELECT id FROM plans WHERE accounting_firm_id = ?", String.class, firm)) {
                jdbc.update("DELETE FROM plan_services WHERE plan_id = ?", plan);
                jdbc.update("DELETE FROM plans WHERE id = ?", plan);
            }
            for (Map<String, Object> company : companies) {
                jdbc.update("DELETE FROM documents WHERE id = ?", company.get("document_id"));
            }
            jdbc.update("DELETE FROM users WHERE accounting_firm_id = ?", firm);
            for (Map<String, Object> user : users) {
                jdbc.update("DELETE FROM documents WHERE id = ?", user.get("document_id"));
            }
            jdbc.update("DELETE FROM accounting_firms WHERE id = ?", firm);
        }
        createdCnpjs.clear();
    }

    @Test
    void signupVerifyTrialLimitBlockAndPlatformConsole() throws Exception {
        // GET /signup/config: open
        JsonNode config = body(mvc.perform(get(BASE + "/signup/config")).andReturn(), 200);
        assertThat(config.get("available").asBoolean()).isTrue();
        assertThat(config.get("termsVersion").asText()).isEqualTo("IT-2026-11");

        // CA1-03 / CT1-07: POST /users anonymous is refused and creates nothing
        assertThat(mvc.perform(post(BASE + "/users").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn()
                .getResponse().getStatus()).isIn(401, 403);

        // CA1-01 / CA1-03: signup with a forged profile → 201 PENDING_VERIFICATION, ADMIN, mail from nao-responda@
        String cnpj = randomCnpj();
        createdCnpjs.add(cnpj);
        String login = "it.dona." + randomDigits(8);
        Map<String, Object> signup = Map.of(
                "firm", Map.of("name", "Escritório Fictício IT", "documentNumber", cnpj, "billingEmail", "fin@exemplo.test",
                        "contactPhone", "(81) 9 9999-0000"),
                "user", Map.of("name", "Dona Fictícia", "document", randomDigits(11), "login", login,
                        "email", "dona@exemplo.test", "password", PASSWORD, "profile", "EMPLOYEE", "accessProfiles", List.of("FISCAL")),
                "termsVersion", "IT-2026-11",
                "profile", "EMPLOYEE");
        String ip = "198.51.100." + (1 + RANDOM.nextInt(250));
        JsonNode created = body(mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", ip).content(json.writeValueAsString(signup))).andReturn(), 201);
        assertThat(created.get("status").asText()).isEqualTo("PENDING_VERIFICATION");
        Map<String, Object> user = jdbc.queryForMap("SELECT id, profile, accounting_firm_id FROM users WHERE login = ?", login);
        assertThat(user.get("profile")).isEqualTo("ADMIN");
        String firmId = (String) user.get("accounting_firm_id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_access_profiles WHERE user_id = ?", Integer.class, user.get("id"))).isZero();
        assertThat(jdbc.queryForObject("SELECT contact_phone FROM accounting_firms WHERE id = ?", String.class, firmId)).isEqualTo("81999990000");
        assertThat(jdbc.queryForObject("SELECT ip FROM terms_acceptances WHERE accounting_firm_id = ?", String.class, firmId)).isEqualTo(ip);
        assertThat(mail.sent).hasSize(1);
        String token = tokenOf(mail.sent.getFirst());

        // CA1-01: login before the verification → 403 error.signup.email.not.verified
        JsonNode notVerified = body(login(login, PASSWORD), 403);
        assertThat(notVerified.get("code").asText()).isEqualTo("error.signup.email.not.verified");
        // review item 1: no other way in - HTTP Basic is off for every route
        assertThat(mvc.perform(get(BASE + "/firm-access").header("Authorization", "Basic "
                + Base64.getEncoder().encodeToString((login + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8))))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        // CA1-02: the link starts the trial until 09/12; the same link again → 400
        JsonNode verified = body(mvc.perform(post(BASE + "/signup/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token)))).andReturn(), 200);
        assertThat(verified.get("trialEndsOn").asText()).isEqualTo("2026-12-09");
        JsonNode again = body(mvc.perform(post(BASE + "/signup/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token)))).andReturn(), 400);
        assertThat(again.get("code").asText()).isEqualTo("error.signup.token.invalid");

        String owner = bearer(body(login(login, PASSWORD), 200));
        JsonNode access = body(mvc.perform(auth(get(BASE + "/firm-access"), owner)).andReturn(), 200);
        assertThat(access.get("status").asText()).isEqualTo("TRIAL");
        assertThat(access.get("trialDaysLeft").asInt()).isEqualTo(14);
        assertThat(access.get("platformAdmin").asBoolean()).isFalse();
        assertThat(access.get("limits").get("maxUsers").asInt()).isEqualTo(2);
        assertThat(access.get("limits").get("usersInUse").asLong()).isEqualTo(1);

        // CA1-15 / CT1-28: limit 2 users with the ADMIN - the 1st teammate goes in, the 2nd → 409 with the details
        assertThat(createUser(owner).getResponse().getStatus()).isEqualTo(200);
        JsonNode limit = body(createUser(owner), 409);
        assertThat(limit.get("code").asText()).isEqualTo("error.plan.limit.users");
        assertThat(limit.get("status").asInt()).isEqualTo(409);
        assertThat(limit.get("kind").asText()).isEqualTo("USER");
        assertThat(limit.get("limit").asInt()).isEqualTo(2);
        assertThat(limit.get("inUse").asInt()).isEqualTo(2);
        assertThat(limit.get("planName").asText()).isEqualTo("Teste grátis");

        // CA1-17 (with users): 1 of 2 in use, two creations at the same time → exactly one goes in
        jdbc.update("UPDATE users SET deleted = 1 WHERE accounting_firm_id = ? AND profile <> 'ADMIN'", firmId);

        // item 1: duplicates on POST /users answer 409 with a code (a room is free, so the limit does not answer first)
        JsonNode loginTaken = body(createUser(owner, login, randomDigits(11), PASSWORD), 409);
        assertThat(loginTaken.get("code").asText()).isEqualTo("error.user.login.already.exists");
        assertThat(loginTaken.get("message").asText()).isEqualTo("Já existe um usuário com esse login.");
        String ownerCpf = jdbc.queryForObject("SELECT d.number FROM users u JOIN documents d ON d.id = u.document_id WHERE u.login = ?",
                String.class, login);
        JsonNode cpfTaken = body(createUser(owner, "it.colab." + randomDigits(8), ownerCpf, PASSWORD), 409);
        assertThat(cpfTaken.get("code").asText()).isEqualTo("error.user.document.already.exists");
        assertThat(cpfTaken.get("message").asText()).isEqualTo("Já existe um documento com esse número e tipo.");
        // item 4: a password over 72 bytes is a 400 with a code, not a 500
        JsonNode longPassword = body(createUser(owner, "it.colab." + randomDigits(8), randomDigits(11), "s".repeat(73)), 400);
        assertThat(longPassword.get("code").asText()).isEqualTo("error.user.password.invalid");

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> statusOf(() -> createUser(owner)));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> statusOf(() -> createUser(owner)));
        assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE accounting_firm_id = ? AND deleted = 0", Integer.class, firmId))
                .isEqualTo(2);

        // CA1-08: 23:30 of the last day still writes (the limit answers, not the block); 00:05 of the next → 403
        clock.set(LocalDateTime.of(2026, 12, 9, 23, 30));
        assertThat(body(createUser(owner), 409).get("code").asText()).isEqualTo("error.plan.limit.users");
        clock.set(LocalDateTime.of(2026, 12, 10, 0, 5));
        JsonNode blocked = body(createUser(owner), 403);
        assertThat(blocked.get("code").asText()).isEqualTo("error.firm.access.blocked");
        // one error format: status is the HTTP status, the firm's situation goes in firmStatus
        assertThat(blocked.get("status").asInt()).isEqualTo(403);
        assertThat(blocked.get("firmStatus").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.has("httpStatus")).isFalse();
        assertThat(blocked.get("message").asText()).isNotBlank();
        assertThat(blocked.has("details")).isTrue();
        assertThat(blocked.has("timestamp")).isTrue();
        assertThat(blocked.get("reason").asText()).isEqualTo("TRIAL_EXPIRED");
        assertThat(blocked.get("blockedSince").asText()).isEqualTo("2026-12-10");

        // CA1-09: still reads - GET and the POST that only searches; the session goes on
        assertThat(mvc.perform(auth(get(BASE + "/firm-access"), owner)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(auth(post(BASE + "/integrations/xmls"), owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isNotEqualTo(403);

        // CA1-21: the console is the platform admin's only
        assertThat(mvc.perform(auth(get(BASE + "/platform/firms"), owner)).andReturn().getResponse().getStatus()).isEqualTo(403);
        String platform = bearer(body(login(PLATFORM_LOGIN, PASSWORD), 200));
        JsonNode page = body(mvc.perform(auth(get(BASE + "/platform/firms").param("q", cnpj), platform)).andReturn(), 200);
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("id").asText()).isEqualTo(firmId);
        assertThat(row.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(row.get("usersInUse").asInt()).isEqualTo(2);
        assertThat(row.get("contactPhone").asText()).isEqualTo("81999990000");
        assertThat(row.get("admins")).hasSize(1);
        assertThat(row.get("admins").get(0).get("email").asText()).isEqualTo("dona@exemplo.test");
        assertThat(row.toString()).doesNotContain("colaborador@exemplo.test");

        // CA1-20: extend-trial until 20/12 → back to TRIAL on the next request, with the event and the admin's login
        JsonNode extended = body(mvc.perform(auth(post(BASE + "/platform/firms/" + firmId + "/access/extend-trial"), platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("trialEndsOn", "2026-12-20", "reason", "negociação")))).andReturn(), 200);
        assertThat(extended.get("status").asText()).isEqualTo("TRIAL");
        assertThat(body(createUser(owner), 409).get("code").asText()).isEqualTo("error.plan.limit.users");
        JsonNode events = body(mvc.perform(auth(get(BASE + "/platform/firms/" + firmId + "/events"), platform)).andReturn(), 200);
        assertThat(events.get(0).get("type").asText()).isEqualTo("TRIAL_EXTENDED");
        assertThat(events.get(0).get("actor").asText()).isEqualTo(PLATFORM_LOGIN);
        assertThat(events.get(0).get("after").get("trialEndsOn").asText()).isEqualTo("2026-12-20");

        // suspend without a reason → 400
        JsonNode noReason = body(mvc.perform(auth(post(BASE + "/platform/firms/" + firmId + "/access/suspend"), platform)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(), 400);
        assertThat(noReason.get("code").asText()).isEqualTo("error.platform.reason.required");

        // CA1-17 / CT1-30: limit 5 companies, 4 in use, two POST /companies at the same time → exactly one 2xx and one 409
        for (int i = 0; i < 4; i++) {
            assertThat(createCompany(owner).getResponse().getStatus()).isEqualTo(200);
        }
        CompletableFuture<Integer> companyA = CompletableFuture.supplyAsync(() -> statusOf(() -> createCompany(owner)));
        CompletableFuture<Integer> companyB = CompletableFuture.supplyAsync(() -> statusOf(() -> createCompany(owner)));
        assertThat(List.of(companyA.get(), companyB.get())).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE accounting_firm_id = ? AND deleted = 0", Integer.class, firmId))
                .isEqualTo(5);
        JsonNode companyLimit = body(createCompany(owner), 409);
        assertThat(companyLimit.get("code").asText()).isEqualTo("error.plan.limit.companies");
        assertThat(companyLimit.get("limit").asInt()).isEqualTo(5);
        assertThat(companyLimit.get("inUse").asInt()).isEqualTo(5);

        // item 2 (F1-RN-13): the platform admin fills the firm's contact data
        clock.set(LocalDateTime.of(2026, 12, 10, 0, 10));
        String contactUrl = BASE + "/platform/firms/" + firmId + "/contact";
        assertThat(mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(contactUrl), owner)
                .contentType(MediaType.APPLICATION_JSON).content("{\"contactPhone\":\"81988887777\"}")).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(body(putContact(platform, contactUrl, Map.of("documentNumber", "11.111.111/0001-11")), 400)
                .get("code").asText()).isEqualTo("error.signup.firm.document.invalid");
        assertThat(body(putContact(platform, contactUrl, Map.of("contactPhone", "9999-0000")), 400)
                .get("code").asText()).isEqualTo("error.signup.phone.invalid");
        assertThat(body(putContact(platform, contactUrl, Map.of("billingEmail", "sem-arroba")), 400)
                .get("code").asText()).isEqualTo("error.signup.billing.email.invalid");
        String otherFirmCnpj = randomCnpj();
        jdbc.update("UPDATE accounting_firms SET document_number = ? WHERE id = ?", otherFirmCnpj, PLATFORM_FIRM);
        assertThat(body(putContact(platform, contactUrl, Map.of("documentNumber", otherFirmCnpj)), 409)
                .get("code").asText()).isEqualTo("error.signup.firm.document.exists");
        JsonNode updated = body(putContact(platform, contactUrl,
                Map.of("billingEmail", "Novo.Financeiro@exemplo.test", "contactPhone", "(81) 3333-4444")), 200);
        assertThat(updated.get("id").asText()).isEqualTo(firmId);
        assertThat(updated.get("billingEmail").asText()).isEqualTo("novo.financeiro@exemplo.test");
        assertThat(updated.get("contactPhone").asText()).isEqualTo("8133334444");
        assertThat(updated.get("documentNumber").asText()).isEqualTo(cnpj);
        JsonNode trail = body(mvc.perform(auth(get(BASE + "/platform/firms/" + firmId + "/events"), platform)).andReturn(), 200);
        assertThat(trail.get(0).get("type").asText()).isEqualTo("CONTACT_UPDATED");
        assertThat(trail.get(0).get("before").get("billingEmail").asText()).isEqualTo("fin@exemplo.test");
        assertThat(trail.get(0).get("after").get("contactPhone").asText()).isEqualTo("8133334444");
        assertThat(body(mvc.perform(auth(get(BASE + "/platform/firms").param("q", cnpj), platform)).andReturn(), 200)
                .get("content").get(0).get("billingEmail").asText()).isEqualTo("novo.financeiro@exemplo.test");
    }

    private MvcResult putContact(String bearer, String url, Map<String, Object> body) throws Exception {
        return mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url), bearer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn();
    }

    private MvcResult createCompany(String bearer) {
        try {
            Map<String, Object> company = Map.of("documentNumber", randomCnpj(), "documentType", "CNPJ",
                    "name", "Empresa Fictícia IT", "active", true);
            return mvc.perform(auth(post(BASE + "/companies"), bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(company))).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * F1-RN-03: um cadastro ainda não verificado é expurgado com os dados pessoais - o console não grava contato dele
     * (409 error.platform.action.invalid) e nada vai para a trilha.
     */
    @Test
    void theContactOfAnUnverifiedSignupIsNotEditable() throws Exception {
        String cnpj = randomCnpj();
        createdCnpjs.add(cnpj);
        Map<String, Object> signup = Map.of(
                "firm", Map.of("name", "Escritório Fictício IT", "documentNumber", cnpj, "billingEmail", "fin@exemplo.test",
                        "contactPhone", "81999990000"),
                "user", Map.of("name", "Fulana", "document", randomDigits(11), "login", "it.pendente." + randomDigits(8),
                        "email", "fulana@exemplo.test", "password", PASSWORD),
                "termsVersion", "IT-2026-11");
        body(mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.51.100." + (1 + RANDOM.nextInt(250)))
                .content(json.writeValueAsString(signup))).andReturn(), 201);
        String firmId = jdbc.queryForObject("SELECT id FROM accounting_firms WHERE document_number = ?", String.class, cnpj);
        String platform = bearer(body(login(PLATFORM_LOGIN, PASSWORD), 200));

        JsonNode refused = body(mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(BASE + "/platform/firms/" + firmId + "/contact"), platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("contactPhone", "81988887777")))).andReturn(), 409);

        assertThat(refused.get("code").asText()).isEqualTo("error.platform.action.invalid");
        assertThat(jdbc.queryForObject("SELECT contact_phone FROM accounting_firms WHERE id = ?", String.class, firmId))
                .isEqualTo("81999990000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firm_subscription_events WHERE accounting_firm_id = ? AND type = 'CONTACT_UPDATED'",
                Integer.class, firmId)).isZero();
    }

    /**
     * QA-02 (decisão do Tony): teste vencido em 09/12 (bloqueado desde 10/12), estendido em 04/01 → dezembro entra na
     * recuperação da SIEG para a empresa com a captura no plano, e o GET /firm-access avisa.
     */
    @Test
    void theReactivationSchedulesTheBlockedMonths() throws Exception {
        String cnpj = randomCnpj();
        createdCnpjs.add(cnpj);
        String login = "it.recupera." + randomDigits(8);
        Map<String, Object> signup = Map.of(
                "firm", Map.of("name", "Escritório Fictício IT", "documentNumber", cnpj, "billingEmail", "fin@exemplo.test",
                        "contactPhone", "81999990000"),
                "user", Map.of("name", "Dona", "document", randomDigits(11), "login", login,
                        "email", "dona@exemplo.test", "password", PASSWORD),
                "termsVersion", "IT-2026-11");
        body(mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.51.100." + (1 + RANDOM.nextInt(250)))
                .content(json.writeValueAsString(signup))).andReturn(), 201);
        body(mvc.perform(post(BASE + "/signup/verify").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", tokenOf(mail.sent.getLast()))))).andReturn(), 200);
        String firmId = jdbc.queryForObject("SELECT id FROM accounting_firms WHERE document_number = ?", String.class, cnpj);
        String owner = bearer(body(login(login, PASSWORD), 200));
        assertThat(createCompany(owner).getResponse().getStatus()).isEqualTo(200);
        String companyId = jdbc.queryForObject("SELECT id FROM companies WHERE accounting_firm_id = ?", String.class, firmId);
        // the company's fee plan with the XML import from SIEG
        String planId = UUID.randomUUID().toString();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO plans (id, deleted, created_date, created_by, name, accounting_firm_id) VALUES (?, 0, ?, 'it', 'Plano IT', ?)",
                planId, now, firmId);
        jdbc.update("INSERT INTO plan_services (plan_id, service) VALUES (?, 'FISCAL_XML_IMPORT'), (?, 'SIEG_INTEGRATION')", planId, planId);
        jdbc.update("INSERT INTO subscriptions (id, deleted, start_date, end_date, created_date, created_by, company_id, plan_id)"
                + " VALUES (?, 0, '2026-01-01', '2027-12-31', ?, 'it', ?, ?)", UUID.randomUUID().toString(), now, companyId, planId);

        // 04/01: blocked since 10/12 (computed); the platform admin extends the trial
        clock.set(LocalDateTime.of(2027, 1, 4, 10, 0));
        String platform = bearer(body(login(PLATFORM_LOGIN, PASSWORD), 200));
        body(mvc.perform(auth(post(BASE + "/platform/firms/" + firmId + "/access/extend-trial"), platform)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("trialEndsOn", "2027-01-20")))).andReturn(), 200);

        assertThat(jdbc.queryForList("SELECT CONCAT(source, '@', period_start) FROM recovery_tasks WHERE accounting_firm_id = ?"
                + " AND status = 'PENDING' ORDER BY source", String.class, firmId))
                .containsExactly("SIEG_NFE_ISSUED@2026-12-01", "SIEG_NFE_RECEIVED@2026-12-01");
        JsonNode access = body(mvc.perform(auth(get(BASE + "/firm-access"), owner)).andReturn(), 200);
        assertThat(access.get("status").asText()).isEqualTo("TRIAL");
        assertThat(access.get("recoveryPending").asBoolean()).isTrue();
        assertThat(access.get("recoveryMonths").get(0).asText()).isEqualTo("2026-12");
        JsonNode events = body(mvc.perform(auth(get(BASE + "/platform/firms/" + firmId + "/events"), platform)).andReturn(), 200);
        assertThat(events.toString()).contains("RECOVERY_SCHEDULED");

        // the same action again does not duplicate anything
        body(mvc.perform(auth(post(BASE + "/platform/firms/" + firmId + "/access/release"), platform)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(), 200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recovery_tasks WHERE accounting_firm_id = ?", Integer.class, firmId))
                .isEqualTo(2);
    }

    /** CA1-06: o login da lista da CS Hub é recusado no cadastro com a resposta de login em uso. */
    @Test
    void theReservedLoginIsRefusedLikeALoginInUse() throws Exception {
        String cnpj = randomCnpj();
        createdCnpjs.add(cnpj);
        Map<String, Object> signup = Map.of(
                "firm", Map.of("name", "Escritório Fictício IT", "documentNumber", cnpj, "billingEmail", "fin@exemplo.test",
                        "contactPhone", "81999990000"),
                "user", Map.of("name", "Fulano", "document", randomDigits(11), "login", PLATFORM_LOGIN,
                        "email", "fulano@exemplo.test", "password", PASSWORD),
                "termsVersion", "IT-2026-11");
        MvcResult result = mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "198.51.100." + (1 + RANDOM.nextInt(250)))
                .content(json.writeValueAsString(signup))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Já existe um usuário com esse login");
        // item 1: the same code as a login in use - the reserved list is not revealed
        assertThat(json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("code").asText())
                .isEqualTo("error.user.login.already.exists");
    }

    /** One error format, in the caller's language: a business error with ?language=en and the errors Spring MVC raises. */
    @Test
    void errorsComeInOneFormatAndInTheRequestedLanguage() throws Exception {
        properties.setSignupEnabled(false);
        try {
            JsonNode closed = body(mvc.perform(post(BASE + "/signup").param("language", "en").contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andReturn(), 503);
            assertThat(closed.get("status").asInt()).isEqualTo(503);
            assertThat(closed.get("code").asText()).isEqualTo("error.signup.unavailable");
            assertThat(closed.get("message").asText()).isEqualTo(messages.getMessage("error.signup.unavailable", null,
                    java.util.Locale.ENGLISH)).doesNotContain("cadastro");
        } finally {
            properties.setSignupEnabled(true);
        }

        // malformed JSON: 400 in the same format, the generic code, never Jackson's text
        JsonNode malformed = body(mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"firm\": ")).andReturn(), 400);
        assertThat(malformed.get("status").asInt()).isEqualTo(400);
        assertThat(malformed.get("code").asText()).isEqualTo("error.generic.bad.request");
        assertThat(malformed.get("message").asText()).isEqualTo("Requisição inválida");
        assertThat(malformed.has("details")).isTrue();

        String platform = bearer(body(login(PLATFORM_LOGIN, PASSWORD), 200));
        // 405 of a route keeps the format (and Spring's Allow header)
        MvcResult wrongMethod = mvc.perform(auth(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(BASE + "/firm-access"), platform)).andReturn();
        JsonNode notAllowed = body(wrongMethod, 405);
        assertThat(notAllowed.get("code").asText()).isEqualTo("error.generic.method.not.allowed");
        assertThat(wrongMethod.getResponse().getHeader("Allow")).contains("GET");
        // a wrong type in the path (not a UUID): 400 with the generic code
        JsonNode wrongType = body(mvc.perform(auth(get(BASE + "/platform/firms/nao-e-uuid/events"), platform)).andReturn(), 400);
        assertThat(wrongType.get("code").asText()).isEqualTo("error.generic.bad.request");
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult createUser(String bearer) {
        return createUser(bearer, "it.colab." + randomDigits(8), randomDigits(11), PASSWORD);
    }

    private MvcResult createUser(String bearer, String login, String cpf, String password) {
        try {
            Map<String, Object> teammate = Map.of("name", "Colaborador Fictício", "documentNumber", cpf,
                    "documentType", "CPF", "login", login, "password", password,
                    "email", "colaborador@exemplo.test", "profile", "EMPLOYEE", "accessProfiles", List.of("FISCAL"));
            return mvc.perform(auth(post(BASE + "/users"), bearer).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(teammate))).andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private MvcResult login(String login, String password) throws Exception {
        String auth = Base64.getEncoder().encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        return mvc.perform(post(BASE + "/auth").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("auth", auth)))).andReturn();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String bearer) {
        return request.header("Authorization", "Bearer " + bearer);
    }

    private static String bearer(JsonNode authenticated) {
        return authenticated.get("token").asText();
    }

    private JsonNode body(MvcResult result, int expectedStatus) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(expectedStatus);
        return json.readTree(content.isEmpty() ? "{}" : content);
    }

    private static String tokenOf(MailMessage message) {
        Matcher matcher = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(message.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String randomDigits(int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            digits.append(RANDOM.nextInt(10));
        }
        return digits.toString();
    }

    /** CNPJ fictício com DV válido, novo a cada execução. */
    private static String randomCnpj() {
        String base = "9" + randomDigits(7) + "0001";
        int d1 = checkDigit(base, new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = checkDigit(base + d1, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    private static int checkDigit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    @SuppressWarnings("unused")
    private static LocalDate day(int month, int day) {
        return LocalDate.of(2026, month, day);
    }
}
