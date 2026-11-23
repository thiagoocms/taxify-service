package br.com.cshub.taxify.integration.nfse;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class AdnClientTest {

    private final AdnClient client = new AdnClient(new AdnProperties(), JsonMapper.builder().build());

    static String gzipBase64(String xml) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    void readsABatchWithGzippedXmls() throws Exception {
        String body = """
                {"StatusProcessamento":"DOCUMENTOS_LOCALIZADOS","LoteDFe":[
                  {"NSU":41,"ChaveAcesso":"%s","TipoDocumento":"NFSE","ArquivoXml":"%s","DataHoraGeracao":"2026-11-20T10:00:00"},
                  {"NSU":42,"ChaveAcesso":"%s","TipoDocumento":"EVENTO","TipoEvento":"CANCELAMENTO","ArquivoXml":"%s"}],
                 "Alertas":[],"Erros":[]}
                """.formatted(NfseXmlTest.NATIONAL_KEY, gzipBase64(NfseXmlTest.national("1", "2")),
                NfseXmlTest.NATIONAL_KEY, gzipBase64("<evento><e101101/></evento>"));

        AdnResponse response = client.parse(200, body.getBytes(StandardCharsets.UTF_8));

        assertThat(response.status()).isEqualTo(AdnResponse.FOUND);
        assertThat(response.documents()).hasSize(2);
        assertThat(response.documents().get(0).isInvoice()).isTrue();
        assertThat(new String(response.documents().get(0).xml(), StandardCharsets.UTF_8)).contains("infNFSe");
        assertThat(response.documents().get(1).isEvent()).isTrue();
        assertThat(response.lastNsu(0)).isEqualTo(42);
        assertThat(response.failed()).isFalse();
        assertThat(response.nothingNew()).isFalse();
    }

    @Test
    void camelCaseFieldsAreReadToo() throws Exception {
        String body = """
                {"statusProcessamento":"DOCUMENTOS_LOCALIZADOS","loteDFe":[{"nsu":"7","chaveAcesso":"x","tipoDocumento":"NFSE","arquivoXml":"%s"}]}
                """.formatted(gzipBase64("<NFSe/>"));

        AdnResponse response = client.parse(200, body.getBytes(StandardCharsets.UTF_8));

        assertThat(response.documents()).hasSize(1);
        assertThat(response.documents().getFirst().nsu()).isEqualTo(7);
    }

    @Test
    void nothingFoundRejectionAndThrottling() throws Exception {
        AdnResponse none = client.parse(404, """
                {"StatusProcessamento":"NENHUM_DOCUMENTO_LOCALIZADO","LoteDFe":[]}""".getBytes(StandardCharsets.UTF_8));
        assertThat(none.nothingNew()).isTrue();
        assertThat(none.failed()).isFalse();

        AdnResponse rejected = client.parse(400, """
                {"StatusProcessamento":"REJEICAO","Erros":[{"Codigo":"E1235","Descricao":"Certificado não pertence ao CNPJ consultado"}]}
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(rejected.failed()).isTrue();
        assertThat(rejected.errors()).containsExactly("E1235 Certificado não pertence ao CNPJ consultado");

        AdnResponse throttled = client.parse(429, new byte[0]);
        assertThat(throttled.throttled()).isTrue();
        assertThat(throttled.failed()).isFalse();

        AdnResponse serverError = client.parse(500, "<html>erro</html>".getBytes(StandardCharsets.UTF_8));
        assertThat(serverError.failed()).isTrue();
        assertThat(serverError.errors()).containsExactly("HTTP 500");
    }
}
