package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.integration.mail.MailMessage;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Contrato de erro unico ({@code ErrorDetailsDTO}: timestamp, status, code, message, details + extras no topo) em cada
 * status, com exemplos reais pelo HTTP contra o MySQL local: 400 (campo e JSON malformado), 401, 403 (bloqueio e
 * permissao), 404, 405, 409 (limite do teste e cadastro duplicado), 415 e 429. O 500 e o 503 estao em
 * {@link ErrorContractServerErrorsIntegrationTest}. Nenhum corpo ecoa texto interno (nome de classe, SQL, parser).
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127\\.0\\.0\\.1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "taxify.saas.signup-enabled=true",
        "taxify.saas.terms-version=QA-2026-11",
        "taxify.platform.admin-logins=qa.err.admin",
        "taxify.platform.firm-id=" + ErrorContractIntegrationTest.PLATFORM_FIRM,
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false"
})
@AutoConfigureMockMvc
class ErrorContractIntegrationTest {

    static final String PLATFORM_FIRM = "00000000-0000-0000-0000-00000000cf04";
    private static final String PLATFORM_LOGIN = "qa.err.admin";
    private static final String PASSWORD = "senha-de-teste-123";
    private static final String BASE = "/api/v1";
    private static final Random RANDOM = new Random();
    /** Texto interno que nunca pode chegar ao cliente. */
    private static final List<String> INTERNAL_TEXT = List.of("Exception", "org.springframework", "com.fasterxml", "tools.jackson",
            "java.lang", "java.sql", "jdbc:", "SQL", "Hibernate", "hibernate", "Unexpected character", "JSON parse", "at br.com");

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(LocalDateTime.of(2026, 11, 26, 10, 0));
        }

        @Bean
        @Primary
        MailSender silentMailSender() {
            return new MailSender() {
                @Override
                public void send(MailMessage message) {
                }

                @Override
                public String from() {
                    return "nao-responda@cshub.com.br";
                }
            };
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

    private final Set<String> firms = new LinkedHashSet<>();
    private final List<String> signupCnpjs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + connection);
        clock.set(LocalDateTime.of(2026, 11, 26, 10, 0));
        cleanUp();
        firm(PLATFORM_FIRM, "CS Hub (teste QA erros)", PLATFORM_LOGIN, null, null);
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ------------------------------------------------------------------ 400

    @Test
    void error400ForAFieldAndForMalformedJson() throws Exception {
        // a business field rule of the signup: its own code
        MvcResult phone = signup(cnpj(), "qa.err." + digits(8), "9999-0000", "203.0.113.1");
        JsonNode body = assertContract(phone, 400, "error.signup.phone.invalid");
        assertThat(body.get("message").asText()).isNotBlank();

        // Bean Validation on a body: the generic validation code, and the message names the field
        Fixture f = trialFirm("2026-12-09");
        String owner = bearer(authenticate(f.login()));
        JsonNode validation = assertContract(mvc.perform(auth(post(BASE + "/companies"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn(), 400, null);
        assertThat(validation.get("code").asText()).isNotBlank();

        // malformed JSON: generic bad request, and nothing of the parser's text
        JsonNode malformed = assertContract(mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"firm\": {")).andReturn(), 400, "error.generic.bad.request");
        assertThat(malformed.get("details").asText()).doesNotContain("{\"firm\"");
        // wrong type of a value
        assertContract(mvc.perform(post(BASE + "/signup/verify").contentType(MediaType.APPLICATION_JSON).content("[1,2]")).andReturn(),
                400, "error.generic.bad.request");
    }

    // ------------------------------------------------------------------ 401

    @Test
    void error401WithoutTokenWithABadTokenAndWithWrongCredentials() throws Exception {
        // BUG-QA-06 (fixed): a missing or invalid token is answered in the single error contract, keeping the Bearer challenge
        MvcResult missing = mvc.perform(get(BASE + "/firm-access")).andReturn();
        MvcResult invalid = mvc.perform(get(BASE + "/firm-access").header("Authorization", "Bearer token.invalido.qa")).andReturn();
        for (MvcResult result : List.of(missing, invalid)) {
            JsonNode unauthorized = assertContract(result, 401, "error.generic.unauthorized");
            assertThat(unauthorized.get("message").asText()).isEqualTo("Sessão inválida ou expirada. Entre de novo.");
            assertThat(unauthorized.get("details").asText()).isEqualTo("uri=" + BASE + "/firm-access");
            assertThat(result.getResponse().getHeader("WWW-Authenticate")).startsWith("Bearer");
        }
        // the invalid token keeps Spring's detail in the challenge header only, never in the body
        assertThat(invalid.getResponse().getHeader("WWW-Authenticate")).contains("invalid_token");
        // wrong password on /auth: 403 (the project convention, kept) with its own code; same answer for an unknown login
        Fixture f = trialFirm("2026-12-09");
        JsonNode wrongPassword = assertContract(authenticate(f.login(), "senha-errada-123"), 403, "error.auth.bad.credentials");
        JsonNode unknownLogin = assertContract(authenticate("login.que.nao.existe.qa", "senha-errada-123"), 403, "error.auth.bad.credentials");
        assertThat(wrongPassword.get("code").asText()).isEqualTo(unknownLogin.get("code").asText());
        assertThat(wrongPassword.get("message").asText()).isEqualTo(unknownLogin.get("message").asText());
    }

    // ------------------------------------------------------------------ 403

    @Test
    void error403ForTheReadOnlyFirmAndForMissingPermission() throws Exception {
        Fixture f = trialFirm("2026-12-09");
        String owner = bearer(authenticate(f.login()));
        clock.set(LocalDateTime.of(2026, 12, 10, 0, 5));
        JsonNode blocked = assertContract(mvc.perform(auth(post(BASE + "/companies"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentNumber\":\"" + cnpj() + "\",\"documentType\":\"CNPJ\",\"name\":\"Empresa QA\",\"active\":true}")).andReturn(),
                403, "error.firm.access.blocked");
        assertThat(blocked.get("firmStatus").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.get("reason").asText()).isEqualTo("TRIAL_EXPIRED");
        assertThat(blocked.get("blockedSince").asText()).isEqualTo("2026-12-10");

        // an ADMIN of an ordinary firm on the platform console
        JsonNode console = assertContract(mvc.perform(auth(get(BASE + "/platform/firms"), owner)).andReturn(), 403, null);
        assertThat(console.get("code").asText()).isNotBlank();
        assertThat(console.has("firmStatus")).isFalse();
    }

    // ------------------------------------------------------------------ 404, 405, 415

    @Test
    void error404Error405AndError415() throws Exception {
        Fixture f = trialFirm("2026-12-09");
        String owner = bearer(authenticate(f.login()));
        assertContract(mvc.perform(auth(get(BASE + "/companies/" + UUID.randomUUID()), owner)).andReturn(), 404, null);
        assertContract(mvc.perform(auth(get(BASE + "/rota/que/nao/existe"), owner)).andReturn(), 404, "error.generic.not.found");

        MvcResult notAllowed = mvc.perform(auth(put(BASE + "/firm-access"), owner).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertContract(notAllowed, 405, "error.generic.method.not.allowed");
        assertThat(notAllowed.getResponse().getHeader("Allow")).as("Spring's Allow header is kept").contains("GET");

        assertContract(mvc.perform(post(BASE + "/signup").contentType(MediaType.TEXT_PLAIN).content("oi")).andReturn(), 415,
                "error.generic.unsupported.media.type");
    }

    // ------------------------------------------------------------------ 409, 429

    @Test
    void error409ForTheTrialLimitAndForADuplicateSignupAndError429() throws Exception {
        Fixture f = trialFirm("2026-12-09");
        for (int i = 0; i < 5; i++) {
            company(f.firmId());
        }
        String owner = bearer(authenticate(f.login()));
        JsonNode limit = assertContract(mvc.perform(auth(post(BASE + "/companies"), owner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentNumber\":\"" + cnpj() + "\",\"documentType\":\"CNPJ\",\"name\":\"Empresa QA\",\"active\":true}")).andReturn(),
                409, "error.plan.limit.companies");
        assertThat(limit.get("limit").asInt()).isEqualTo(5);
        assertThat(limit.get("inUse").asInt()).isEqualTo(5);
        assertThat(limit.get("planName").asText()).isEqualTo("Teste grátis");
        assertThat(limit.get("kind").asText()).isEqualTo("COMPANY");

        // duplicate signup (same CNPJ)
        String cnpj = cnpj();
        assertThat(signup(cnpj, "qa.err." + digits(8), "81999990000", "203.0.113.10").getResponse().getStatus()).isEqualTo(201);
        JsonNode duplicate = assertContract(signup(cnpj, "qa.err." + digits(8), "81999990000", "203.0.113.11"), 409,
                "error.signup.firm.document.exists");
        assertThat(duplicate.get("message").asText()).contains("CNPJ");

        // 429: the 4th signup of the same IP on the same day
        for (int i = 0; i < 2; i++) {
            assertThat(signup(cnpj(), "qa.err." + digits(8), "81999990000", "203.0.113.20").getResponse().getStatus()).isEqualTo(201);
        }
        assertThat(signup(cnpj(), "qa.err." + digits(8), "81999990000", "203.0.113.20").getResponse().getStatus()).isEqualTo(201);
        assertContract(signup(cnpj(), "qa.err." + digits(8), "81999990000", "203.0.113.20"), 429, "error.signup.rate.limited");
    }

    // ------------------------------------------------------------------ the contract itself

    /**
     * The body of every error: timestamp (number), status = the HTTP status, code, message, details; JSON; no internal text.
     *
     * @param code the expected code, or null to only require a non-blank one
     */
    private JsonNode assertContract(MvcResult result, int status, String code) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).as("content type of " + status).contains("json");
        JsonNode body = json.readTree(content);
        assertThat(body.get("timestamp").isNumber()).as("timestamp in " + content).isTrue();
        assertThat(body.get("status").asInt()).as("status in " + content).isEqualTo(status);
        assertThat(body.get("code").asText()).as("code in " + content).isNotBlank();
        if (code != null) {
            assertThat(body.get("code").asText()).as(content).isEqualTo(code);
        }
        assertThat(body.get("message").asText()).as("message in " + content).isNotBlank();
        assertThat(body.has("details")).as("details in " + content).isTrue();
        for (String internal : INTERNAL_TEXT) {
            assertThat(content).as("internal text '%s' in the %d body", internal, status).doesNotContain(internal);
        }
        return body;
    }

    // ------------------------------------------------------------------ helpers

    private record Fixture(String firmId, String login) {
    }

    private MvcResult signup(String cnpj, String login, String phone, String ip) throws Exception {
        signupCnpjs.add(cnpj);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("firm", Map.of("name", "Escritório Fictício QA", "documentNumber", cnpj, "billingEmail", "fin@example.com", "contactPhone", phone));
        request.put("user", Map.of("name", "Dona Fictícia QA", "document", cpf(), "login", login, "email", login + "@example.com", "password", PASSWORD));
        request.put("termsVersion", "QA-2026-11");
        return mvc.perform(post(BASE + "/signup").contentType(MediaType.APPLICATION_JSON).header("X-Forwarded-For", ip)
                .content(json.writeValueAsString(request))).andReturn();
    }

    private MvcResult authenticate(String login) throws Exception {
        return authenticate(login, PASSWORD);
    }

    private MvcResult authenticate(String login, String password) throws Exception {
        String auth = Base64.getEncoder().encodeToString((login + ":" + password).getBytes(StandardCharsets.UTF_8));
        return mvc.perform(post(BASE + "/auth").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("auth", auth)))).andReturn();
    }

    private String bearer(MvcResult authenticated) throws Exception {
        String content = authenticated.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(authenticated.getResponse().getStatus()).as(content).isEqualTo(200);
        return json.readTree(content).get("token").asText();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String bearer) {
        return request.header("Authorization", "Bearer " + bearer);
    }

    private Fixture trialFirm(String trialEndsOn) {
        String firmId = UUID.randomUUID().toString();
        String login = "qa.err." + digits(8);
        firm(firmId, "Escritorio QA Erros", login, "TRIAL", trialEndsOn);
        return new Fixture(firmId, login);
    }

    private void company(String firmId) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        String documentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CNPJ')", documentId, now, cnpj());
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active)"
                + " VALUES (?, 0, ?, 'qa', ?, ?, 'Empresa Ficticia QA', 1)", UUID.randomUUID().toString(), now, firmId, documentId);
    }

    private void firm(String firmId, String name, String adminLogin, String status, String trialEndsOn) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'qa', ?)", firmId, now, name);
        firms.add(firmId);
        String documentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'qa', ?, 'CPF')", documentId, now, cpf());
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login, name,"
                        + " email, password) VALUES (?, 0, ?, 'qa', 'ADMIN', ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), now, firmId, documentId, adminLogin, "Admin " + name, adminLogin + "@example.com",
                passwordEncoder.encode(PASSWORD));
        if (status != null) {
            jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, trial_ends_on, migrated, source, deleted, created_by,"
                    + " created_date) VALUES (?, ?, ?, ?, 0, 'MANUAL', 0, 'qa', ?)", UUID.randomUUID().toString(), firmId, status, trialEndsOn, now);
        }
    }

    private void cleanUp() {
        List<String> all = new ArrayList<>(firms);
        all.add(PLATFORM_FIRM);
        for (String cnpj : signupCnpjs) {
            all.addAll(jdbc.queryForList("SELECT id FROM accounting_firms WHERE document_number = ?", String.class, cnpj));
        }
        all.addAll(jdbc.queryForList("SELECT accounting_firm_id FROM users WHERE login = ?", String.class, PLATFORM_LOGIN));
        for (String firm : all.stream().distinct().toList()) {
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
        firms.clear();
        signupCnpjs.clear();
    }

    private static String digits(int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            digits.append(RANDOM.nextInt(10));
        }
        return digits.toString();
    }

    private static String cnpj() {
        String base = "9" + digits(7) + "0001";
        int d1 = cnpjDigit(base, new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int d2 = cnpjDigit(base + d1, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return base + d1 + d2;
    }

    private static int cnpjDigit(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

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
