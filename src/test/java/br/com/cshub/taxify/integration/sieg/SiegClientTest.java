package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiegClientTest {

    @Mock
    private SiegFeignClient feignClient;
    @Mock
    private MessageService messageService;

    private static final String API_KEY = "test-api-key";

    private SiegClient client;

    @BeforeEach
    void setUp() {
        client = new SiegClient(feignClient, new FiscalXmlPackageExtractor(), messageService);
        lenient().when(messageService.getMessage(any())).thenReturn("error");
    }

    @Test
    void throwsWhenApiKeyIsMissing() {
        assertThatThrownBy(() -> client.baixarXmls(sampleRequest(), " "))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void contarXmlsNfeThrowsWhenApiKeyIsMissing() {
        assertThatThrownBy(() -> client.contarXmlsNfe(sampleRequest(), " "))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void extractsASingleXmlBodyAsReturnedRaw() {
        String xml = "<nfeProc></nfeProc>";
        when(feignClient.baixarXmls(any(), any())).thenReturn(jsonResponse(200, xml.getBytes(StandardCharsets.UTF_8)));

        List<byte[]> result = client.baixarXmls(sampleRequest(), API_KEY);

        assertThat(result).hasSize(1);
        assertThat(new String(result.get(0), StandardCharsets.UTF_8)).isEqualTo(xml);
    }

    @Test
    void unzipsTheZipBodyReturnedBySieg() throws Exception {
        String xmlOne = "<nfeProc>1</nfeProc>";
        String xmlTwo = "<nfeProc>2</nfeProc>";

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            zos.putNextEntry(new ZipEntry("one.xml"));
            zos.write(xmlOne.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("two.xml"));
            zos.write(xmlTwo.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        when(feignClient.baixarXmls(any(), any())).thenReturn(jsonResponse(200, zipBytes.toByteArray()));

        List<byte[]> result = client.baixarXmls(sampleRequest(), API_KEY);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(b -> new String(b, StandardCharsets.UTF_8)))
                .containsExactlyInAnyOrder(xmlOne, xmlTwo);
    }

    @Test
    void throwsWhenSiegRespondsWithAnErrorStatus() {
        when(feignClient.baixarXmls(any(), any()))
                .thenReturn(jsonResponse(400, "{\"success\":false,\"message\":\"boom\"}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.baixarXmls(sampleRequest(), API_KEY))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void contarXmlsNfeReturnsTheNfeCountFromASuccessfulResponse() {
        when(feignClient.contarXmls(any(), any())).thenReturn(new SiegContarXmlsResponse(
                true, "", 200, new SiegContarXmlsDataDTO(310, 999, 0, 0, 0), false));

        assertThat(client.contarXmlsNfe(sampleRequest(), API_KEY)).isEqualTo(310);
    }

    @Test
    void contarXmlsNfeReturnsZeroWhenNfeFieldIsNull() {
        when(feignClient.contarXmls(any(), any())).thenReturn(new SiegContarXmlsResponse(
                true, "", 200, new SiegContarXmlsDataDTO(null, 999, 0, 0, 0), false));

        assertThat(client.contarXmlsNfe(sampleRequest(), API_KEY)).isZero();
    }

    @Test
    void contarXmlsNfeThrowsWhenTheResponseReportsFailure() {
        when(feignClient.contarXmls(any(), any())).thenReturn(new SiegContarXmlsResponse(
                false, "boom", 500, null, true));

        assertThatThrownBy(() -> client.contarXmlsNfe(sampleRequest(), API_KEY))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void contarXmlsNfePropagatesFeignExceptionAsSiegIntegrationException() {
        Request request = Request.create(Request.HttpMethod.POST, "/api/v1/contar-xmls",
                Map.of(), null, new RequestTemplate());
        when(feignClient.contarXmls(any(), any()))
                .thenThrow(new FeignException.InternalServerError("boom", request,
                        "err".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThatThrownBy(() -> client.contarXmlsNfe(sampleRequest(), API_KEY))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void propagatesFeignExceptionAsSiegIntegrationException() {
        Request request = Request.create(Request.HttpMethod.POST, "/api/v1/baixar-xmls",
                Map.of(), null, new RequestTemplate());
        when(feignClient.baixarXmls(any(), any()))
                .thenThrow(new FeignException.InternalServerError("boom", request,
                        "err".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThatThrownBy(() -> client.baixarXmls(sampleRequest(), API_KEY))
                .isInstanceOf(SiegIntegrationException.class);
    }

    private Response jsonResponse(int status, byte[] body) {
        Request request = Request.create(Request.HttpMethod.POST, "/api/v1/baixar-xmls",
                Map.of(), null, new RequestTemplate());
        return Response.builder()
                .status(status)
                .request(request)
                .body(body)
                .build();
    }

    private SiegBaixarXmlsRequest sampleRequest() {
        return new SiegBaixarXmlsRequest(1, 50, 0, null, null, "12345678000199", null);
    }
}
