package br.com.cshub.taxify.integration.nfse;

import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Client of the Ambiente Nacional NFS-e distribution for taxpayers ("API contribuintes"):
 * {@code GET /DFe/{NSU}?cnpjConsulta=...&lote=true} with the company's A1 (mutual TLS). Each document
 * comes as base64 of a gzipped XML (ArquivoXml). The NSU passed is the last one read - a document seen
 * again is skipped by the persistence, so the "from" or "after" reading of the parameter never loses one.
 */
@Component
public class AdnClient {

    private final AdnProperties properties;
    private final ObjectMapper objectMapper;

    public AdnClient(AdnProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AdnResponse fetch(A1Certificate certificate, String cnpj, long nsu) throws IOException, GeneralSecurityException {
        URI uri = URI.create(properties.endpoint() + "/DFe/" + nsu + "?cnpjConsulta=" + cnpj + "&lote=true");
        try (HttpClient http = httpClient(certificate.sslContext())) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return parse(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling the Ambiente Nacional", e);
        }
    }

    static HttpClient httpClient(SSLContext sslContext) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    AdnResponse parse(int httpStatus, byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return new AdnResponse(httpStatus, null, List.of(),
                    httpStatus / 100 == 2 || httpStatus == 404 || httpStatus == 429 ? List.of() : List.of("HTTP " + httpStatus));
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            if (httpStatus / 100 != 2) {
                return new AdnResponse(httpStatus, null, List.of(), List.of("HTTP " + httpStatus));
            }
            throw new IOException("Ambiente Nacional answer is not JSON", e);
        }
        String status = text(field(root, "StatusProcessamento"));
        List<AdnResponse.Document> documents = new ArrayList<>();
        JsonNode lote = field(root, "LoteDFe");
        if (lote != null && lote.isArray()) {
            for (JsonNode item : lote) {
                String file = text(field(item, "ArquivoXml"));
                if (file == null || file.isBlank()) {
                    continue;
                }
                JsonNode nsuNode = field(item, "NSU");
                long nsu = nsuNode == null ? 0 : nsuNode.isNumber() ? nsuNode.asLong() : parseLong(text(nsuNode));
                documents.add(new AdnResponse.Document(nsu, text(field(item, "ChaveAcesso")), text(field(item, "TipoDocumento")),
                        text(field(item, "TipoEvento")), decode(file)));
            }
        }
        List<String> errors = new ArrayList<>();
        JsonNode errorList = field(root, "Erros");
        if (errorList != null && errorList.isArray()) {
            for (JsonNode error : errorList) {
                String code = text(field(error, "Codigo"));
                String description = text(field(error, "Descricao"));
                String complement = text(field(error, "Complemento"));
                errors.add(String.join(" ", List.of(code == null ? "" : code, description == null ? "" : description,
                        complement == null ? "" : complement)).strip());
            }
        }
        return new AdnResponse(httpStatus, status, documents, errors);
    }

    /** Base64 of gzip (as the ADN sends) - or plain base64 XML, just in case. */
    static byte[] decode(String base64) throws IOException {
        byte[] raw = Base64.getMimeDecoder().decode(base64.strip());
        if (raw.length > 2 && (raw[0] & 0xff) == 0x1f && (raw[1] & 0xff) == 0x8b) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return in.readAllBytes();
            }
        }
        return raw;
    }

    /** The field by name, ignoring case ("LoteDFe" or "loteDFe"). */
    private static JsonNode field(JsonNode node, String name) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode exact = node.get(name);
        if (exact != null) {
            return exact;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
