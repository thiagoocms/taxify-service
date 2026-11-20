package br.com.cshub.taxify.integration.acessorias;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.exception.AcessoriasIntegrationException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcessoriasClientTest {

    @Mock private AcessoriasFeignClient feignClient;
    @Mock private MessageService messageService;

    private AcessoriasClient client;

    @BeforeEach
    void setUp() {
        AcessoriasProperties properties = new AcessoriasProperties();
        properties.setBaseUrl("https://api.acessorias.com/");
        client = new AcessoriasClient(feignClient, properties, JsonMapper.builder().build(), messageService);
        lenient().when(messageService.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(messageService.getMessage(anyString(), any(String[].class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void pagesThroughCompaniesWithBareFlagsUntilAnEmptyPage() {
        when(feignClient.get(any(), eq("Bearer tok"))).thenReturn(
                response(200, "[{\"ID\":\"1\",\"Identificador\":\"38.217.129/0001-29\",\"Razao\":\"ACME\",\"Departamentos\":[{\"Nome\":\"Fiscal\",\"RespEmail\":\"a@b.com\"}]}]"),
                response(200, "[]"));

        List<AcessoriasCompanyDTO> companies = client.listCompanies(" tok ", LocalDateTime.of(2026, 11, 19, 3, 0, 5));

        assertThat(companies).hasSize(1);
        assertThat(companies.getFirst().departamentos().getFirst().respEmail()).isEqualTo("a@b.com");
        ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
        verify(feignClient, times(2)).get(uris.capture(), eq("Bearer tok"));
        assertThat(uris.getAllValues().getFirst()).hasToString(
                "https://api.acessorias.com/companies/ListAll/?departments&registrationData&stateRegistrations"
                        + "&DtLastDH=2026-11-19%2003%3A00%3A05&Pagina=1");
        assertThat(uris.getAllValues().get(1).toString()).endsWith("&Pagina=2");
    }

    @Test
    void listsUsersAndTreatsNothingFoundAsTheEnd() {
        when(feignClient.get(any(), anyString())).thenReturn(
                response(200, "[{\"id\":\"17\",\"nome\":\"João\",\"email\":\"joao@x.com\",\"status\":\"Ativo\"}]"),
                response(200, "{\"Erro\":\"Nenhum registro encontrado.\"}"));

        List<AcessoriasUserDTO> users = client.listUsers("tok");

        assertThat(users).containsExactly(new AcessoriasUserDTO("17", "João", "joao@x.com", "Ativo"));
        ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
        verify(feignClient, times(2)).get(uris.capture(), anyString());
        assertThat(uris.getAllValues().getFirst()).hasToString("https://api.acessorias.com/users/ListAll?Pagina=1");
    }

    @Test
    void anErroObjectWithHttp200IsAFailure() {
        when(feignClient.get(any(), anyString())).thenReturn(
                response(200, "{\"Erro\":\"Formato inválido para o parâmetro DtLastDH.\"}"));

        assertThatThrownBy(() -> client.listCompanies("tok", null))
                .isInstanceOf(AcessoriasIntegrationException.class)
                .hasMessage("error.acessorias.api.error");
    }

    @Test
    void unauthorizedMeansInvalidToken() {
        when(feignClient.get(any(), anyString())).thenReturn(response(401, ""));

        assertThatThrownBy(() -> client.listUsers("tok"))
                .isInstanceOf(AcessoriasIntegrationException.class)
                .hasMessage("error.acessorias.api.token.invalid");
    }

    @Test
    void missingTokenFailsWithoutCallingTheApi() {
        assertThatThrownBy(() -> client.listUsers(" "))
                .hasMessage("error.acessorias.api.token.missing");
    }

    private static Response response(int status, String body) {
        Request request = Request.create(Request.HttpMethod.GET, "https://api.acessorias.com", Map.of(), null, StandardCharsets.UTF_8, null);
        return Response.builder().status(status).request(request).headers(Map.of())
                .body(body, StandardCharsets.UTF_8).build();
    }
}
