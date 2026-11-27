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
        assertThatThrownBy(() -> client.downloadXmls(sampleRequest(), " "))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void countXmlsThrowsWhenApiKeyIsMissing() {
        assertThatThrownBy(() -> client.countXmls(sampleRequest(), " "))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void extractsASingleXmlBodyAsReturnedRaw() {
        String xml = "<nfeProc></nfeProc>";
        when(feignClient.downloadXmls(any(), any())).thenReturn(jsonResponse(200, xml.getBytes(StandardCharsets.UTF_8)));

        List<byte[]> result = client.downloadXmls(sampleRequest(), API_KEY);

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
        when(feignClient.downloadXmls(any(), any())).thenReturn(jsonResponse(200, zipBytes.toByteArray()));

        List<byte[]> result = client.downloadXmls(sampleRequest(), API_KEY);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(b -> new String(b, StandardCharsets.UTF_8)))
                .containsExactlyInAnyOrder(xmlOne, xmlTwo);
    }

    @Test
    void throwsWhenSiegRespondsWithAnErrorStatus() {
        when(feignClient.downloadXmls(any(), any()))
                .thenReturn(jsonResponse(400, "{\"success\":false,\"message\":\"boom\"}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.downloadXmls(sampleRequest(), API_KEY))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void countXmlsReturnsTheNfeAndNfseCountsFromASuccessfulResponse() {
        when(feignClient.countXmls(any(), any())).thenReturn(new SiegCountXmlsResponse(
                true, "", 200, new SiegCountXmlsDataDTO(310, 999, 0, 0, 12), false));

        assertThat(client.countXmls(sampleRequest(), API_KEY)).isEqualTo(new SiegDocumentCounts(310, 12));
    }

    @Test
    void countXmlsReturnsZeroWhenAFieldIsNull() {
        when(feignClient.countXmls(any(), any())).thenReturn(new SiegCountXmlsResponse(
                true, "", 200, new SiegCountXmlsDataDTO(null, 999, 0, 0, null), false));

        assertThat(client.countXmls(sampleRequest(), API_KEY)).isEqualTo(new SiegDocumentCounts(0, 0));
    }

    @Test
    void countXmlsThrowsWhenTheResponseReportsFailure() {
        when(feignClient.countXmls(any(), any())).thenReturn(new SiegCountXmlsResponse(
                false, "boom", 500, null, true));

        assertThatThrownBy(() -> client.countXmls(sampleRequest(), API_KEY))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void countXmlsPropagatesFeignExceptionAsSiegIntegrationException() {
        Request request = Request.create(Request.HttpMethod.POST, "/api/v1/contar-xmls",
                Map.of(), null, new RequestTemplate());
        when(feignClient.countXmls(any(), any()))
                .thenThrow(new FeignException.InternalServerError("boom", request,
                        "err".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThatThrownBy(() -> client.countXmls(sampleRequest(), API_KEY))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void propagatesFeignExceptionAsSiegIntegrationException() {
        Request request = Request.create(Request.HttpMethod.POST, "/api/v1/baixar-xmls",
                Map.of(), null, new RequestTemplate());
        when(feignClient.downloadXmls(any(), any()))
                .thenThrow(new FeignException.InternalServerError("boom", request,
                        "err".getBytes(StandardCharsets.UTF_8), Map.of()));

        assertThatThrownBy(() -> client.downloadXmls(sampleRequest(), API_KEY))
                .isInstanceOf(SiegIntegrationException.class);
    }

    @Test
    void notFoundWithNoXmlMessageIsAnEmptyPageNotAFailure() {
        when(feignClient.downloadXmls(any(), any())).thenReturn(jsonResponse(404,
                "{\"IsSuccess\":false,\"ErrorMessage\":\"Nenhum arquivo XML localizado.\",\"StatusCode\":404}"
                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(client.downloadXmls(sampleRequest(), API_KEY)).isEmpty();
    }

    @Test
    void otherNotFoundIsStillAFailure() {
        when(feignClient.downloadXmls(any(), any())).thenReturn(jsonResponse(404,
                "{\"ErrorMessage\":\"Rota inexistente\"}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.downloadXmls(sampleRequest(), API_KEY)).isInstanceOf(SiegIntegrationException.class);
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

    private SiegDownloadXmlsRequest sampleRequest() {
        return new SiegDownloadXmlsRequest(1, 50, 0, null, null, "12345678000199", null, true);
    }
}
