package br.com.cshub.taxify.integration.sefaz;

import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends "Ciência da Operação" (tpEvento 210210) to the Ambiente Nacional's NFeRecepcaoEvento4:
 * an envEvento of up to 20 events, each one signed (XMLDSig, RSA-SHA1, enveloped, C14N - the
 * NF-e layout) with the recipient's A1, over the same mutual TLS as the distribution.
 */
@Component
public class SefazEventClient {

    /** SEFAZ takes at most 20 events per envEvento. */
    public static final int MAX_PER_BATCH = 20;
    public static final String CIENCIA = "210210";
    public static final String CIENCIA_DESCRIPTION = "Ciencia da Operacao";

    private static final String NS_NFE = SefazDistClient.NS_NFE;
    private static final String WSDL = "http://www.portalfiscal.inf.br/nfe/wsdl/NFeRecepcaoEvento4";
    /** cOrgao 91 = Ambiente Nacional, where the recipient's manifestation goes. */
    private static final String ORGAO_AN = "91";
    private static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final DateTimeFormatter DH_EVENTO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    private final SefazProperties properties;

    public SefazEventClient(SefazProperties properties) {
        this.properties = properties;
    }

    public SefazEventResponse sendCiencia(A1Certificate certificate, String cnpj, List<String> accessKeys)
            throws IOException, GeneralSecurityException {
        if (accessKeys.isEmpty() || accessKeys.size() > MAX_PER_BATCH) {
            throw new IllegalArgumentException("1 to " + MAX_PER_BATCH + " notes per batch");
        }
        // a minute back: SEFAZ refuses a dhEvento ahead of its own clock
        OffsetDateTime when = OffsetDateTime.now(BRASILIA).minusMinutes(1);
        String envEvento = signedEnvEvento(certificate, properties.tpAmb(), cnpj, accessKeys, when,
                String.valueOf(System.currentTimeMillis()));
        try (HttpClient http = SefazDistClient.httpClient(certificate.sslContext())) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.eventEndpoint()))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/soap+xml; charset=utf-8; action=\"" + WSDL + "/nfeRecepcaoEventoNF\"")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope(envEvento), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("SEFAZ answered HTTP " + response.statusCode() + " to the event");
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling SEFAZ", e);
        }
    }

    static String envelope(String envEvento) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap12:Envelope xmlns:soap12=\"http://www.w3.org/2003/05/soap-envelope\"><soap12:Body>"
                + "<nfeDadosMsg xmlns=\"" + WSDL + "\">" + envEvento + "</nfeDadosMsg>"
                + "</soap12:Body></soap12:Envelope>";
    }

    /** infEvento Id: "ID" + tpEvento + chave + nSeqEvento in 2 digits. */
    static String eventId(String accessKey) {
        return "ID" + CIENCIA + accessKey + "01";
    }

    /** The envEvento with one signed ciência per key - no XML declaration, no whitespace between tags. */
    static String signedEnvEvento(A1Certificate certificate, String tpAmb, String cnpj, List<String> accessKeys,
                                  OffsetDateTime when, String lote) throws GeneralSecurityException {
        StringBuilder xml = new StringBuilder("<envEvento xmlns=\"").append(NS_NFE).append("\" versao=\"1.00\"><idLote>")
                .append(lote).append("</idLote>");
        for (String key : accessKeys) {
            xml.append("<evento versao=\"1.00\"><infEvento Id=\"").append(eventId(key)).append("\">")
                    .append("<cOrgao>").append(ORGAO_AN).append("</cOrgao>")
                    .append("<tpAmb>").append(tpAmb).append("</tpAmb>")
                    .append("<CNPJ>").append(cnpj).append("</CNPJ>")
                    .append("<chNFe>").append(key).append("</chNFe>")
                    .append("<dhEvento>").append(DH_EVENTO.format(when)).append("</dhEvento>")
                    .append("<tpEvento>").append(CIENCIA).append("</tpEvento>")
                    .append("<nSeqEvento>1</nSeqEvento><verEvento>1.00</verEvento>")
                    .append("<detEvento versao=\"1.00\"><descEvento>").append(CIENCIA_DESCRIPTION).append("</descEvento></detEvento>")
                    .append("</infEvento></evento>");
        }
        xml.append("</envEvento>");

        Document doc;
        try {
            doc = FiscalXmlDomParser.parse(xml.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the event XML", e);
        }
        PrivateKey key = certificate.privateKey();
        NodeList eventos = doc.getElementsByTagNameNS(NS_NFE, "evento");
        for (int i = 0; i < eventos.getLength(); i++) {
            Element evento = (Element) eventos.item(i);
            Element infEvento = (Element) evento.getElementsByTagNameNS(NS_NFE, "infEvento").item(0);
            sign(evento, infEvento, key, certificate);
        }
        // The JDK wraps base64 at 76 columns with CRs ("&#13;"), which SEFAZ refuses. SignatureValue and
        // the certificate aren't covered by the signature (only SignedInfo is), so this changes nothing signed.
        for (String tag : List.of("SignatureValue", "X509Certificate")) {
            NodeList nodes = doc.getElementsByTagNameNS(DSIG_NS, tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                nodes.item(i).setTextContent(nodes.item(i).getTextContent().replaceAll("\\s", ""));
            }
        }
        return serialize(doc);
    }

    /** Enveloped signature of infEvento, appended to evento - RSA-SHA1 and C14N as the NF-e layout asks. */
    private static void sign(Element evento, Element infEvento, PrivateKey key, A1Certificate certificate)
            throws GeneralSecurityException {
        infEvento.setIdAttribute("Id", true);
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        List<Transform> transforms = List.of(
                factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                factory.newTransform(CanonicalizationMethod.INCLUSIVE, (TransformParameterSpec) null));
        Reference reference = factory.newReference("#" + infEvento.getAttribute("Id"),
                factory.newDigestMethod(DigestMethod.SHA1, null), transforms, null, null);
        SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                factory.newSignatureMethod(SignatureMethod.RSA_SHA1, null), List.of(reference));
        KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
        KeyInfo keyInfo = keyInfoFactory.newKeyInfo(List.of(keyInfoFactory.newX509Data(List.of(certificate.x509()))));
        try {
            factory.newXMLSignature(signedInfo, keyInfo).sign(new DOMSignContext(key, evento));
        } catch (javax.xml.crypto.MarshalException | javax.xml.crypto.dsig.XMLSignatureException e) {
            throw new GeneralSecurityException("Could not sign the event", e);
        }
    }

    private static String serialize(Document doc) {
        try {
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the event XML", e);
        }
    }

    static SefazEventResponse parse(byte[] body) throws IOException {
        Document doc;
        try {
            doc = FiscalXmlDomParser.parse(body);
        } catch (Exception e) {
            throw new IOException("SEFAZ answer is not XML", e);
        }
        NodeList rets = doc.getElementsByTagNameNS(NS_NFE, "retEnvEvento");
        if (rets.getLength() == 0) {
            throw new IOException("SEFAZ answer without retEnvEvento");
        }
        Element ret = (Element) rets.item(0);
        List<SefazEventResponse.Result> results = new ArrayList<>();
        NodeList infos = ret.getElementsByTagNameNS(NS_NFE, "retEvento");
        for (int i = 0; i < infos.getLength(); i++) {
            Element info = (Element) ((Element) infos.item(i)).getElementsByTagNameNS(NS_NFE, "infEvento").item(0);
            if (info != null) {
                results.add(new SefazEventResponse.Result(text(info, "chNFe"), text(info, "cStat"), text(info, "xMotivo"),
                        text(info, "nProt"), text(info, "dhRegEvento")));
            }
        }
        return new SefazEventResponse(directText(ret, "cStat"), directText(ret, "xMotivo"), results);
    }

    /** A child of retEnvEvento itself - the batch's own cStat, not one of its events'. */
    private static String directText(Element parent, String tag) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && tag.equals(element.getLocalName())) {
                return element.getTextContent().strip();
            }
        }
        return null;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagNameNS(NS_NFE, tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().strip();
    }
}
