package br.com.cshub.taxify.integration.sefaz;

import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SefazEventClientTest {

    private static final String KEY_1 = "26241111222333000181550010000000011000000010";
    private static final String KEY_2 = "26241111222333000181550010000000021000000025";
    private static final String DS = "http://www.w3.org/2000/09/xmldsig#";

    @Test
    void eachCienciaIsSignedOverItsInfEventoAndVerifiesWithTheCertificate() throws Exception {
        A1Certificate certificate = A1Certificate.open(A1CertificateTest.testPfx(), "teste123");
        String xml = SefazEventClient.signedEnvEvento(certificate, "2", "11222333000181", List.of(KEY_1, KEY_2),
                OffsetDateTime.of(2026, 11, 23, 10, 0, 0, 0, ZoneOffset.ofHours(-3)), "1234");

        assertThat(xml).startsWith("<envEvento xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"1.00\"><idLote>1234</idLote>")
                .contains("<infEvento Id=\"ID210210" + KEY_1 + "01\"><cOrgao>91</cOrgao><tpAmb>2</tpAmb><CNPJ>11222333000181</CNPJ>")
                .contains("<dhEvento>2026-11-23T10:00:00-03:00</dhEvento><tpEvento>210210</tpEvento>")
                .contains("<descEvento>Ciencia da Operacao</descEvento>")
                .contains("<Signature xmlns=\"" + DS + "\">") // no ds: prefix - SEFAZ wants the default namespace
                .doesNotContain("\n")
                .doesNotContain("&#13;");

        Document doc = FiscalXmlDomParser.parse(xml.getBytes(StandardCharsets.UTF_8));
        NodeList signatures = doc.getElementsByTagNameNS(DS, "Signature");
        assertThat(signatures.getLength()).isEqualTo(2);
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        for (int i = 0; i < signatures.getLength(); i++) {
            Element signature = (Element) signatures.item(i);
            Element evento = (Element) signature.getParentNode();
            assertThat(evento.getLocalName()).isEqualTo("evento");
            ((Element) evento.getElementsByTagNameNS(SefazDistClient.NS_NFE, "infEvento").item(0)).setIdAttribute("Id", true);
            DOMValidateContext context = new DOMValidateContext(certificate.x509().getPublicKey(), signature);
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE); // SHA-1, as the NF-e layout requires
            XMLSignature parsed = factory.unmarshalXMLSignature(context);
            assertThat(parsed.validate(context)).isTrue();
        }
    }

    @Test
    void readsEachEventResultOfAProcessedBatch() throws Exception {
        String body = """
                <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"><soap:Body>
                <nfeResultMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeRecepcaoEvento4">
                <retEnvEvento versao="1.00" xmlns="http://www.portalfiscal.inf.br/nfe">
                  <idLote>1234</idLote><tpAmb>1</tpAmb><verAplic>AN_1.0</verAplic><cOrgao>91</cOrgao>
                  <cStat>128</cStat><xMotivo>Lote de evento processado</xMotivo>
                  <retEvento versao="1.00"><infEvento><tpAmb>1</tpAmb><cOrgao>91</cOrgao><cStat>135</cStat>
                    <xMotivo>Evento registrado e vinculado a NF-e</xMotivo><chNFe>%s</chNFe><tpEvento>210210</tpEvento>
                    <dhRegEvento>2026-11-23T10:00:05-03:00</dhRegEvento><nProt>891260000000001</nProt></infEvento></retEvento>
                  <retEvento versao="1.00"><infEvento><tpAmb>1</tpAmb><cOrgao>91</cOrgao><cStat>573</cStat>
                    <xMotivo>Rejeicao: Duplicidade de evento</xMotivo><chNFe>%s</chNFe></infEvento></retEvento>
                </retEnvEvento></nfeResultMsg></soap:Body></soap:Envelope>
                """.formatted(KEY_1, KEY_2);

        SefazEventResponse response = SefazEventClient.parse(body.getBytes(StandardCharsets.UTF_8));

        assertThat(response.processed()).isTrue();
        assertThat(response.cStat()).isEqualTo("128");
        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).registered()).isTrue();
        assertThat(response.results().get(0).protocol()).isEqualTo("891260000000001");
        assertThat(response.results().get(1).registered()).isFalse();
        assertThat(response.results().get(1).alreadyDone()).isTrue();
    }

    @Test
    void aBatchIsOneToTwentyNotes() {
        SefazEventClient client = new SefazEventClient(new SefazProperties());
        assertThatThrownBy(() -> client.sendCiencia(null, "11222333000181", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsAResNFe() {
        String xml = """
                <resNFe versao="1.01" xmlns="http://www.portalfiscal.inf.br/nfe"><chNFe>%s</chNFe><CNPJ>99888777000166</CNPJ>
                <xNome>FORNECEDOR LTDA</xNome><IE>123456</IE><dhEmi>2026-11-20T09:30:00-03:00</dhEmi><tpNF>1</tpNF>
                <vNF>1530.75</vNF><digVal>abc=</digVal><dhRecbto>2026-11-20T09:31:00-03:00</dhRecbto>
                <nProt>126260000000099</nProt><cSitNFe>1</cSitNFe></resNFe>
                """.formatted(KEY_1);

        NoteSummaryXml summary = NoteSummaryXml.parse(xml.getBytes(StandardCharsets.UTF_8)).orElseThrow();

        assertThat(summary.accessKey()).isEqualTo(KEY_1);
        assertThat(summary.issuerDocument()).isEqualTo("99888777000166");
        assertThat(summary.issuerName()).isEqualTo("FORNECEDOR LTDA");
        assertThat(summary.issueDate()).isEqualTo("2026-11-20T09:30:00");
        assertThat(summary.totalValue()).isEqualByComparingTo("1530.75");
        assertThat(summary.situation()).isEqualTo("1");
        assertThat(summary.protocol()).isEqualTo("126260000000099");
        assertThat(NoteSummaryXml.parse("<procNFe/>".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void theKeyQueryAsksForOneNote() {
        String envelope = SefazDistClient.envelopeWith("1", "26", "11222333000181",
                "<consChNFe><chNFe>" + KEY_1 + "</chNFe></consChNFe>");

        assertThat(envelope).contains("<CNPJ>11222333000181</CNPJ><consChNFe><chNFe>" + KEY_1 + "</chNFe></consChNFe></distDFeInt>");
    }
}
