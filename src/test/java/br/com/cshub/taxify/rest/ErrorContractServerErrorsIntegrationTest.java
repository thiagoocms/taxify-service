package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.service.firmaccess.SignupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Contrato de erro: 503 do cadastro fechado (padrao, pelo servico real, nas duas linguas pelo ?language=) e 500 sem eco
 * do texto interno (um servico espionado lanca excecao com texto de SQL e de caminho interno).
 */
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = "localhost|127[.]0[.]0[.]1",
        disabledReason = "integration test: only against a local MySQL (DB_HOST=localhost)")
@SpringBootTest(properties = {
        "fiscal-xml.sync.enabled=false", "fiscal-xml.rectification.enabled=false", "fiscal-xml.purge.enabled=false",
        "sefaz.dist.enabled=false", "nfse.adn.enabled=false", "audit.enabled=false", "pendencies.enabled=false",
        "acessorias.sync-enabled=false", "billing.sync-enabled=false", "efisco.sync.enabled=false",
        "efisco.certidao.enabled=false"
})
@AutoConfigureMockMvc
class ErrorContractServerErrorsIntegrationTest {

    private static final String SECRET = "jdbc:mysql://segredo-interno:3306/banco select * from users where cpf = 12345678901";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @MockitoSpyBean
    private SignupService signupService;

    @Test
    void error503OfTheClosedSignupFollowsTheLanguageParameter() throws Exception {
        JsonNode pt = body(mvc.perform(post("/api/v1/signup").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(), 503);
        assertThat(pt.get("code").asText()).isEqualTo("error.signup.unavailable");
        assertThat(pt.get("status").asInt()).isEqualTo(503);
        assertThat(pt.get("message").asText()).isEqualTo("O cadastro de novos escritórios não está disponível no momento. Fale com a CS Hub.");
        assertThat(pt.get("timestamp").isNumber()).isTrue();
        assertThat(pt.has("details")).isTrue();

        JsonNode en = body(mvc.perform(post("/api/v1/signup?language=en").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(), 503);
        assertThat(en.get("code").asText()).isEqualTo("error.signup.unavailable");
        assertThat(en.get("message").asText()).isEqualTo("Signing up new firms is not available right now. Please contact CS Hub.");

        // the closed signup also says so in /signup/config
        JsonNode config = body(mvc.perform(get("/api/v1/signup/config")).andReturn(), 200);
        assertThat(config.get("available").asBoolean()).isFalse();
    }

    /** BUG-QA-06 (fixed): the 401 of a missing token is in the error contract, in the requested language. */
    @Test
    void error401MessageFollowsTheLanguageParameter() throws Exception {
        JsonNode en = body(mvc.perform(get("/api/v1/firm-access?language=en")).andReturn(), 401);
        assertThat(en.get("code").asText()).isEqualTo("error.generic.unauthorized");
        assertThat(en.get("message").asText()).isEqualTo("Invalid or expired session. Log in again.");
    }

    @Test
    void error500NeverEchoesTheInternalText() throws Exception {
        doThrow(new IllegalStateException(SECRET)).when(signupService).config();
        MvcResult result = mvc.perform(get("/api/v1/signup/config")).andReturn();
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(500);
        JsonNode body = json.readTree(content);
        assertThat(body.get("code").asText()).isEqualTo("error.generic.internal");
        assertThat(body.get("status").asInt()).isEqualTo(500);
        assertThat(body.get("message").asText()).isEqualTo("Erro interno.");
        assertThat(content).doesNotContain("segredo-interno").doesNotContain("jdbc:").doesNotContain("select").doesNotContain("12345678901")
                .doesNotContain("IllegalStateException").doesNotContain("br.com.cshub");

        MvcResult english = mvc.perform(get("/api/v1/signup/config?language=en")).andReturn();
        assertThat(english.getResponse().getStatus()).isEqualTo(500);
        assertThat(json.readTree(english.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("message").asText()).isEqualTo("Internal error.");
    }

    private JsonNode body(MvcResult result, int status) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(status);
        return json.readTree(content);
    }
}
