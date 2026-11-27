package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CT-CP-14 / CA-CP-08 (P-CP-09): an error answer of SIEG whose body has a CNPJ and a token (fictitious) - the client
 * logs only the status, the exception carries the status and never the body.
 */
class SiegClientSafeLogTest {

    private static final String BODY = "{\"Erro\":\"CNPJ 12345678000190 sem permissao\",\"token\":\"token=abc123\"}";

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(SiegClient.class);
    private SiegFeignClient feignClient;
    private SiegClient client;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        feignClient = mock(SiegFeignClient.class);
        MessageService messages = mock(MessageService.class);
        when(messages.getMessage(any())).thenReturn("Falha ao comunicar com a API da SIEG.");
        client = new SiegClient(feignClient, new FiscalXmlPackageExtractor(), messages);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.POST, "/BaixarXmls", Map.of(), null, new RequestTemplate());
    }

    @Test
    void baixarXmlsErrorKeepsOnlyTheStatus() {
        when(feignClient.baixarXmls(any(), any())).thenReturn(Response.builder().status(400).request(request())
                .body(BODY.getBytes(StandardCharsets.UTF_8)).build());

        assertThatThrownBy(() -> client.baixarXmls(new SiegBaixarXmlsRequest(1, 50, 0, null, null, null, "11594485000105", true), "key"))
                .isInstanceOfSatisfying(SiegIntegrationException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).doesNotContain("12345678000190").doesNotContain("abc123");
                });
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("12345678000190").doesNotContain("abc123"));
        assertThat(logs.list.getFirst().getFormattedMessage()).contains("HTTP 400");
    }

    @Test
    void contarXmlsErrorKeepsOnlyTheStatus() {
        FeignException error = FeignException.errorStatus("contarXmls", Response.builder().status(401).request(request())
                .body(BODY, StandardCharsets.UTF_8).build());
        when(feignClient.contarXmls(any(), any())).thenThrow(error);

        assertThatThrownBy(() -> client.contarXmls(new SiegBaixarXmlsRequest(1, 50, 0, null, null, null, "11594485000105", null), "key"))
                .isInstanceOfSatisfying(SiegIntegrationException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(401);
                    assertThat(e.getCause()).as("the Feign exception (with the body) is not carried").isNull();
                });
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain("12345678000190").doesNotContain("abc123");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
