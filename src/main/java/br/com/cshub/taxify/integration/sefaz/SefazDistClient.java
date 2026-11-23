package br.com.cshub.taxify.integration.sefaz;

import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * SOAP 1.2 client for the Ambiente Nacional's NFeDistribuicaoDFe (distNSU): asks for the
 * documents after {@code ultNSU} for a CNPJ, authenticating with that company's A1 (mutual
 * TLS). No captcha, no browser. Documents come gzipped and base64-encoded in docZip.
 */
@Component
public class SefazDistClient {

    static final String NS_NFE = "http://www.portalfiscal.inf.br/nfe";

    /** IBGE state codes - cUFAutor is the UF of the company asking. */
    private static final Map<String, String> UF_CODES = Map.ofEntries(
            Map.entry("RO", "11"), Map.entry("AC", "12"), Map.entry("AM", "13"), Map.entry("RR", "14"),
            Map.entry("PA", "15"), Map.entry("AP", "16"), Map.entry("TO", "17"), Map.entry("MA", "21"),
            Map.entry("PI", "22"), Map.entry("CE", "23"), Map.entry("RN", "24"), Map.entry("PB", "25"),
            Map.entry("PE", "26"), Map.entry("AL", "27"), Map.entry("SE", "28"), Map.entry("BA", "29"),
            Map.entry("MG", "31"), Map.entry("ES", "32"), Map.entry("RJ", "33"), Map.entry("SP", "35"),
            Map.entry("PR", "41"), Map.entry("SC", "42"), Map.entry("RS", "43"), Map.entry("MS", "50"),
            Map.entry("MT", "51"), Map.entry("GO", "52"), Map.entry("DF", "53"));

    private final SefazProperties properties;

    public SefazDistClient(SefazProperties properties) {
        this.properties = properties;
    }

    public static String ufCode(String uf) {
        return uf == null ? null : UF_CODES.get(uf.strip().toUpperCase());
    }

    public SefazDistResponse fetch(A1Certificate certificate, String uf, String cnpj, String ultNsu)
            throws IOException, GeneralSecurityException {
        SSLContext sslContext = certificate.sslContext();
        try (HttpClient http = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(30))
                .build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.endpoint()))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/soap+xml; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope(properties.tpAmb(), ufCode(uf), cnpj, ultNsu), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("SEFAZ answered HTTP " + response.statusCode());
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling SEFAZ", e);
        }
    }

    static String envelope(String tpAmb, String cUfAutor, String cnpj, String ultNsu) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope"><soap12:Body>\
                <nfeDistDFeInteresse xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeDistribuicaoDFe"><nfeDadosMsg>\
                <distDFeInt xmlns="%s" versao="1.01"><tpAmb>%s</tpAmb><cUFAutor>%s</cUFAutor><CNPJ>%s</CNPJ>\
                <distNSU><ultNSU>%s</ultNSU></distNSU></distDFeInt>\
                </nfeDadosMsg></nfeDistDFeInteresse></soap12:Body></soap12:Envelope>"""
                .formatted(NS_NFE, tpAmb, cUfAutor, cnpj, ultNsu);
    }

    static SefazDistResponse parse(byte[] body) throws IOException {
        org.w3c.dom.Document doc;
        try {
            doc = FiscalXmlDomParser.parse(body);
        } catch (Exception e) {
            throw new IOException("SEFAZ answer is not XML", e);
        }
        NodeList rets = doc.getElementsByTagNameNS(NS_NFE, "retDistDFeInt");
        if (rets.getLength() == 0) {
            throw new IOException("SEFAZ answer without retDistDFeInt");
        }
        Element ret = (Element) rets.item(0);
        List<SefazDistResponse.Document> documents = new ArrayList<>();
        NodeList zips = ret.getElementsByTagNameNS(NS_NFE, "docZip");
        for (int i = 0; i < zips.getLength(); i++) {
            Element zip = (Element) zips.item(i);
            documents.add(new SefazDistResponse.Document(zip.getAttribute("NSU"), zip.getAttribute("schema"),
                    gunzip(Base64.getMimeDecoder().decode(zip.getTextContent().strip()))));
        }
        return new SefazDistResponse(text(ret, "cStat"), text(ret, "xMotivo"), text(ret, "ultNSU"), text(ret, "maxNSU"), documents);
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagNameNS(NS_NFE, tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().strip();
    }
}
