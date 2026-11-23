package br.com.cshub.taxify.integration.sefaz;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SefazDistClientTest {

    private static String zip(String xml) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    void envelopeCarriesEnvironmentUfCnpjAndNsu() {
        String envelope = SefazDistClient.envelope("1", "26", "11222333000181", "000000000000123");

        assertThat(envelope).startsWith("<?xml")
                .contains("<tpAmb>1</tpAmb><cUFAutor>26</cUFAutor><CNPJ>11222333000181</CNPJ>")
                .contains("<distNSU><ultNSU>000000000000123</ultNSU></distNSU>")
                .contains("xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"1.01\"");
    }

    @Test
    void ufCodes() {
        assertThat(SefazDistClient.ufCode("PE")).isEqualTo("26");
        assertThat(SefazDistClient.ufCode(" sp ")).isEqualTo("35");
        assertThat(SefazDistClient.ufCode("XX")).isNull();
        assertThat(SefazDistClient.ufCode(null)).isNull();
    }

    @Test
    void parsesTheBatchAndUnzipsEachDocument() throws IOException {
        String note = "<nfeProc xmlns=\"http://www.portalfiscal.inf.br/nfe\"><NFe/></nfeProc>";
        String summary = "<resNFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><chNFe>1</chNFe></resNFe>";
        String body = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"><soap:Body>
                <nfeDistDFeInteresseResponse xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeDistribuicaoDFe">
                <nfeDistDFeInteresseResult>
                <retDistDFeInt xmlns="http://www.portalfiscal.inf.br/nfe" versao="1.01">
                  <tpAmb>1</tpAmb><cStat>138</cStat><xMotivo>Documento(s) localizado(s)</xMotivo>
                  <ultNSU>000000000000002</ultNSU><maxNSU>000000000000009</maxNSU>
                  <loteDistDFeInt>
                    <docZip NSU="000000000000001" schema="procNFe_v4.00.xsd">%s</docZip>
                    <docZip NSU="000000000000002" schema="resNFe_v1.01.xsd">%s</docZip>
                  </loteDistDFeInt>
                </retDistDFeInt>
                </nfeDistDFeInteresseResult></nfeDistDFeInteresseResponse></soap:Body></soap:Envelope>
                """.formatted(zip(note), zip(summary));

        SefazDistResponse response = SefazDistClient.parse(body.getBytes(StandardCharsets.UTF_8));

        assertThat(response.cStat()).isEqualTo("138");
        assertThat(response.ultNsu()).isEqualTo("000000000000002");
        assertThat(response.maxNsu()).isEqualTo("000000000000009");
        assertThat(response.caughtUp()).isFalse();
        assertThat(response.documents()).hasSize(2);
        assertThat(response.documents().get(0).isFullNote()).isTrue();
        assertThat(new String(response.documents().get(0).xml(), StandardCharsets.UTF_8)).isEqualTo(note);
        assertThat(response.documents().get(1).isNoteSummary()).isTrue();
    }

    @Test
    void nothingNewIsCaughtUp() throws IOException {
        String body = """
                <retDistDFeInt xmlns="http://www.portalfiscal.inf.br/nfe" versao="1.01"><cStat>137</cStat>
                <xMotivo>Nenhum documento localizado</xMotivo><ultNSU>000000000000009</ultNSU><maxNSU>000000000000009</maxNSU>
                </retDistDFeInt>""";

        SefazDistResponse response = SefazDistClient.parse(body.getBytes(StandardCharsets.UTF_8));

        assertThat(response.caughtUp()).isTrue();
        assertThat(response.documents()).isEmpty();
    }

    @Test
    void answerWithoutTheResultIsAnError() {
        assertThatThrownBy(() -> SefazDistClient.parse("<html>erro</html>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);
    }
}
