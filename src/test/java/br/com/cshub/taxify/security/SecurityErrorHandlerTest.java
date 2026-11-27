package br.com.cshub.taxify.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.servlet.i18n.SessionLocaleResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** BUG-QA-06: the 401/403 of the security filters in the single error body, with the Bearer challenge and ?language=. */
class SecurityErrorHandlerTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final SecurityErrorHandler handler;

    SecurityErrorHandlerTest() {
        ReloadableResourceBundleMessageSource messages = new ReloadableResourceBundleMessageSource();
        messages.setBasenames("classpath:/i18n/errors");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        SessionLocaleResolver locales = new SessionLocaleResolver();
        locales.setDefaultLocale(Locale.forLanguageTag("pt-BR"));
        handler = new SecurityErrorHandler(messages, locales, json);
    }

    private static MockHttpServletRequest request(String language) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/firm-access");
        if (language != null) {
            request.setParameter("language", language);
        }
        return request;
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void the401IsInTheErrorContractWithTheBearerChallenge() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.commence(request(null), response, new InsufficientAuthenticationException("Full authentication is required"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(response.getContentType()).contains("application/json");
        JsonNode body = body(response);
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("code").asText()).isEqualTo("error.generic.unauthorized");
        assertThat(body.get("message").asText()).isEqualTo("Sessão inválida ou expirada. Entre de novo.");
        assertThat(body.get("details").asText()).isEqualTo("uri=/api/v1/firm-access");
        assertThat(body.get("timestamp").isNumber()).isTrue();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("Full authentication");
    }

    @Test
    void theMessageFollowsTheLanguageParameter() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.commence(request("en"), response, new InsufficientAuthenticationException("x"));
        assertThat(body(response).get("message").asText()).isEqualTo("Invalid or expired session. Log in again.");

        assertThat(handler.locale(request("not a locale!"))).isEqualTo(Locale.forLanguageTag("pt-BR"));
    }

    @Test
    void the403OfTheFiltersIsInTheErrorContractToo() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handle(request(null), response, new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = body(response);
        assertThat(body.get("code").asText()).isEqualTo("error.access.denied");
        assertThat(body.get("message").asText()).startsWith("Você não tem permissão");
    }
}
