package br.com.cshub.taxify.integration.nfse;

import java.util.List;

/**
 * One batch of the ADN distribution: the processing status, the documents (NFS-e or events, XML
 * already decoded) and the errors SEFIN/ADN reported.
 *
 * @param httpStatus the HTTP status of the answer (the ADN answers "nothing found" with 404 and a body)
 */
public record AdnResponse(int httpStatus, String status, List<Document> documents, List<String> errors) {

    public static final String FOUND = "DOCUMENTOS_LOCALIZADOS";
    public static final String NONE = "NENHUM_DOCUMENTO_LOCALIZADO";
    public static final String REJECTED = "REJEICAO";

    /** One distributed document. {@code type}: NFSE or EVENTO. */
    public record Document(long nsu, String accessKey, String type, String eventType, byte[] xml) {

        public boolean isInvoice() {
            return type == null || type.toUpperCase().contains("NFSE");
        }

        public boolean isEvent() {
            return type != null && type.toUpperCase().contains("EVENTO");
        }
    }

    public boolean nothingNew() {
        return NONE.equalsIgnoreCase(status) || (documents.isEmpty() && errors.isEmpty() && httpStatus / 100 == 2);
    }

    /** Asked too often (HTTP 429) - wait before the next run. */
    public boolean throttled() {
        return httpStatus == 429;
    }

    public boolean failed() {
        return REJECTED.equalsIgnoreCase(status) || (!errors.isEmpty() && documents.isEmpty())
                || (httpStatus >= 400 && !nothingNew() && !throttled());
    }

    public long lastNsu(long fallback) {
        return documents.stream().mapToLong(Document::nsu).max().orElse(fallback);
    }
}
