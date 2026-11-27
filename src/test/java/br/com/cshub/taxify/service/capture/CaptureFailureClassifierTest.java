package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.RetryableException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.security.GeneralSecurityException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** CP-RN-11: the code by the error's type, never its text; provider_status only digits (CT-CP-13 a CT-CP-15). */
class CaptureFailureClassifierTest {

    private static SiegIntegrationException http(int status) {
        return new SiegIntegrationException("error.sieg.integration.failed", "Falha ao comunicar com a API da SIEG.", status);
    }

    /** CT-CP-13 and the HTTP table. */
    @Test
    void siegHttpStatusesMapToTheirCodes() {
        assertThat(CaptureFailureClassifier.sieg(http(401))).isEqualTo(new CaptureFailureClassifier.Classified(CaptureFailureCode.SIEG_AUTH, "401"));
        assertThat(CaptureFailureClassifier.sieg(http(403)).code()).isEqualTo(CaptureFailureCode.SIEG_AUTH);
        assertThat(CaptureFailureClassifier.sieg(http(429)).code()).isEqualTo(CaptureFailureCode.SIEG_RATE_LIMITED);
        assertThat(CaptureFailureClassifier.sieg(http(400))).isEqualTo(new CaptureFailureClassifier.Classified(CaptureFailureCode.SIEG_REJECTED, "400"));
        assertThat(CaptureFailureClassifier.sieg(http(503))).isEqualTo(new CaptureFailureClassifier.Classified(CaptureFailureCode.SIEG_UNAVAILABLE, "503"));
    }

    /** CT-CP-14: a message carrying a CNPJ and a token never reaches the classification. */
    @Test
    void theMessageTextIsNeverRead() {
        SiegIntegrationException sensitive = new SiegIntegrationException("error.sieg.integration.failed",
                "corpo: 12345678000190 token=abc123 HTTP 401 timeout", 400);
        CaptureFailureClassifier.Classified classified = CaptureFailureClassifier.sieg(sensitive);
        assertThat(classified.code()).isEqualTo(CaptureFailureCode.SIEG_REJECTED);
        assertThat(classified.providerStatus()).isEqualTo("400");
    }

    @Test
    void feignStatusesAndNetworkFailures() {
        Request request = Request.create(Request.HttpMethod.POST, "/contar-xmls", Map.of(), null, new RequestTemplate());
        FeignException unavailable = FeignException.errorStatus("contarXmls", feign.Response.builder().status(502)
                .request(request).body("{\"cnpj\":\"12345678000190\"}", StandardCharsets.UTF_8).build());
        assertThat(CaptureFailureClassifier.sieg(new SiegIntegrationException("error.sieg.integration.failed", "x", unavailable)))
                .isEqualTo(new CaptureFailureClassifier.Classified(CaptureFailureCode.SIEG_UNAVAILABLE, "502"));

        RetryableException timeout = new RetryableException(-1, "Read timed out", Request.HttpMethod.POST,
                new SocketTimeoutException("Read timed out"), (Long) null, request);
        assertThat(CaptureFailureClassifier.sieg(new SiegIntegrationException("error.sieg.integration.failed", "x", timeout)).code())
                .isEqualTo(CaptureFailureCode.SIEG_TIMEOUT);
        RetryableException refused = new RetryableException(-1, "refused", Request.HttpMethod.POST,
                new ConnectException("Connection refused"), (Long) null, request);
        assertThat(CaptureFailureClassifier.sieg(refused).code()).isEqualTo(CaptureFailureCode.SIEG_CONNECTION);
    }

    @Test
    void siegCodesWithoutStatus() {
        assertThat(CaptureFailureClassifier.sieg(new SiegIntegrationException("error.sieg.api.key.missing", "x")).code())
                .isEqualTo(CaptureFailureCode.SIEG_API_KEY_MISSING);
        assertThat(CaptureFailureClassifier.sieg(new SiegIntegrationException("error.sieg.xml.storage.failed", "x")).code())
                .isEqualTo(CaptureFailureCode.XML_STORAGE_FAILED);
        // success = false / unreadable package: no status, no network cause
        assertThat(CaptureFailureClassifier.sieg(new SiegIntegrationException("error.sieg.integration.failed", "x")).code())
                .isEqualTo(CaptureFailureCode.SIEG_INVALID_RESPONSE);
        assertThat(CaptureFailureClassifier.sieg(new IllegalStateException("12345678000190")).code())
                .isEqualTo(CaptureFailureCode.INTERNAL_ERROR);
    }

    /** CT-CP-15: "4O0" and "12345" are dropped. */
    @Test
    void providerStatusAcceptsOnlyUpToFourDigits() {
        assertThat(CaptureFailureClassifier.providerStatus("4O0")).isNull();
        assertThat(CaptureFailureClassifier.providerStatus("12345")).isNull();
        assertThat(CaptureFailureClassifier.providerStatus("token=abc123")).isNull();
        assertThat(CaptureFailureClassifier.providerStatus(" 593 ")).isEqualTo("593");
        assertThat(CaptureFailureClassifier.providerStatus("200")).isEqualTo("200");
    }

    @Test
    void directCaptureCodes() {
        assertThat(CaptureFailureClassifier.sefazIo(new SocketTimeoutException())).isEqualTo(CaptureFailureCode.SEFAZ_TIMEOUT);
        assertThat(CaptureFailureClassifier.sefazIo(new java.io.IOException("x"))).isEqualTo(CaptureFailureCode.SEFAZ_CONNECTION);
        assertThat(CaptureFailureClassifier.adnIo(new SocketTimeoutException())).isEqualTo(CaptureFailureCode.ADN_TIMEOUT);
        assertThat(CaptureFailureClassifier.adnIo(new ConnectException())).isEqualTo(CaptureFailureCode.ADN_CONNECTION);
        assertThat(CaptureFailureClassifier.certificate(new NoSuchFileException("x"))).isEqualTo(CaptureFailureCode.CERT_FILE_MISSING);
        assertThat(CaptureFailureClassifier.certificate(new GeneralSecurityException("x"))).isEqualTo(CaptureFailureCode.CERT_INVALID);
    }
}
