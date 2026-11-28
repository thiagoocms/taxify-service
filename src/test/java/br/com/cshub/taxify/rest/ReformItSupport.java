package br.com.cshub.taxify.rest;

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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Fictitious scenario and HTTP helpers of the Reforma Tributária integration tests (F1a-0 and F5a): firms, users,
 * companies, portfolio and the login, all with a random prefix and removed at the end. CNPJs with valid check digits;
 * e-mails {@code @exemplo.test}. Only against the local MySQL (the tests check the JDBC URL).
 */
final class ReformItSupport {

    static final String BASE = "/api/v1";
    static final String PASSWORD = "SenhaDeTeste2026";
    private static final Random RANDOM = new Random();

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final LocalDateTime now;
    final String prefix;
    final List<String> firms = new ArrayList<>();

    ReformItSupport(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc, PasswordEncoder passwordEncoder, LocalDateTime now,
                    String prefixRoot) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.now = now;
        this.prefix = prefixRoot + Integer.toHexString(RANDOM.nextInt(0xfffff)) + ".";
    }

    void assumeLocalDatabase() {
        String connection = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getURL());
        org.junit.jupiter.api.Assumptions.assumeTrue(connection != null
                        && (connection.startsWith("jdbc:mysql://localhost:") || connection.startsWith("jdbc:mysql://127.0.0.1:")),
                "only against a local MySQL - connected to " + connection);
    }

    // ------------------------------------------------------------------ scenario

    String firm(String name, String status) {
        String id = UUID.randomUUID().toString();
        rawFirm(id, name + " " + prefix, status);
        firms.add(id);
        return id;
    }

    void rawFirm(String id, String name, String status) {
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update("INSERT INTO accounting_firms (id, deleted, created_date, created_by, name) VALUES (?, 0, ?, 'it', ?)", id, at, name);
        if (status != null) {
            jdbc.update("INSERT INTO firm_subscriptions (id, accounting_firm_id, status, migrated, source, deleted, created_by, created_date)"
                    + " VALUES (?, ?, ?, 0, 'MANUAL', 0, 'it', ?)", UUID.randomUUID().toString(), id, status, at);
        }
    }

    String company(String firm, String name, String cnpj, String taxRegimeType) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', ?, 'CNPJ')",
                document, at, cnpj);
        jdbc.update("INSERT INTO companies (id, deleted, created_date, created_by, accounting_firm_id, document_id, name, active, uf,"
                + " tax_regime_type) VALUES (?, 0, ?, 'it', ?, ?, ?, 1, 'PE', ?)", id, at, firm, document, name, taxRegimeType);
        return id;
    }

    String user(String firm, String login, String profile, String accessProfile) {
        String id = UUID.randomUUID().toString();
        String document = UUID.randomUUID().toString();
        Timestamp at = Timestamp.valueOf(now);
        jdbc.update("INSERT INTO documents (id, deleted, created_date, created_by, number, type) VALUES (?, 0, ?, 'it', ?, 'CPF')",
                document, at, digits(11));
        jdbc.update("INSERT INTO users (id, deleted, created_date, created_by, profile, accounting_firm_id, document_id, login,"
                        + " name, email, password) VALUES (?, 0, ?, 'it', ?, ?, ?, ?, ?, ?, ?)",
                id, at, profile, firm, document, login(login), "Pessoa " + login + " Fictícia",
                login + "." + prefix + "@exemplo.test", passwordEncoder.encode(PASSWORD));
        if (accessProfile != null) {
            jdbc.update("INSERT INTO user_access_profiles (user_id, access_profile) VALUES (?, ?)", id, accessProfile);
        }
        return id;
    }

    void portfolio(String user, String company) {
        jdbc.update("INSERT INTO users_companies (user_id, company_id, role, deleted, created_date, created_by)"
                + " VALUES (?, ?, 'MEMBER', 0, ?, 'it')", user, company, Timestamp.valueOf(now));
    }

    String login(String login) {
        return login.startsWith("it.") ? login : prefix + login;
    }

    void removeFirm(String firm) {
        List<String> companies = jdbc.queryForList("SELECT id FROM companies WHERE accounting_firm_id = ?", String.class, firm);
        for (String company : companies) {
            jdbc.update("DELETE e FROM stock_credit_survey_events e JOIN stock_credit_surveys s ON s.id = e.survey_id"
                    + " WHERE s.company_id = ?", company);
            jdbc.update("DELETE FROM stock_credit_surveys WHERE company_id = ?", company);
            jdbc.update("DELETE i FROM fiscal_document_items i JOIN fiscal_documents d ON d.id = i.document_id"
                    + " WHERE d.company_id = ?", company);
            for (String table : List.of("fiscal_document_sources", "fiscal_documents", "company_tax_regimes", "users_companies")) {
                jdbc.update("DELETE FROM " + table + " WHERE company_id = ?", company);
            }
        }
        List<Map<String, Object>> users = jdbc.queryForList("SELECT id, document_id FROM users WHERE accounting_firm_id = ?", firm);
        for (Map<String, Object> user : users) {
            for (String table : List.of("user_notification_settings", "password_reset_tokens", "user_access_profiles", "users_companies")) {
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

    void removeAll() {
        for (String firm : firms) {
            removeFirm(firm);
        }
        firms.clear();
    }

    // ------------------------------------------------------------------ fictitious numbers

    /** A fictitious CNPJ with valid check digits: 8-digit root + establishment. */
    static String cnpj(String root, String establishment) {
        String base = root + establishment;
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int d1 = check(base, w1);
        int d2 = check(base + d1, w2);
        return base + d1 + d2;
    }

    static String randomCnpj() {
        return cnpj(digits(8), "0001");
    }

    private static int check(String digits, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    static String digits(int length) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < length; i++) {
            value.append(RANDOM.nextInt(10));
        }
        return value.toString();
    }

    // ------------------------------------------------------------------ HTTP

    String token(String login) throws Exception {
        String auth = Base64.getEncoder().encodeToString((login(login) + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        MvcResult result = mvc.perform(post(BASE + "/auth").contentType(MediaType.APPLICATION_JSON).content("{\"auth\":\"" + auth + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("login " + login).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn();
    }

    JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    JsonNode ok(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult result = call(request, token);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        return body(result);
    }

    static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    String code(MvcResult result) throws Exception {
        JsonNode body = body(result);
        return body.has("code") ? body.get("code").asText() : null;
    }
}
