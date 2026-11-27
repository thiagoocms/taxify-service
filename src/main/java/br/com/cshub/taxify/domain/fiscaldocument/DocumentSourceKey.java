package br.com.cshub.taxify.domain.fiscaldocument;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a fiscal document was seen (FD-RN-40, FD-RN-31): the key of a provider ({@code SIEG}, {@code SEFAZ_DFE},
 * {@code NFSE_ADN} and future ones) or an internal origin ({@code UPLOAD}). An open value stored as
 * {@code varchar(30)} in {@code fiscal_document_sources.source} and {@code fiscal_documents.source_system}. Only the
 * shape is checked here; the registered providers are checked by the capture core.
 */
public record DocumentSourceKey(String value) {

    public static final int MAX_LENGTH = ProviderKey.MAX_LENGTH;
    private static final Pattern SHAPE = Pattern.compile("[A-Z][A-Z0-9_]{0," + (MAX_LENGTH - 1) + "}");

    public static final DocumentSourceKey SIEG = ProviderKey.SIEG.asSource();
    public static final DocumentSourceKey SEFAZ_DFE = ProviderKey.SEFAZ_DFE.asSource();
    public static final DocumentSourceKey NFSE_ADN = ProviderKey.NFSE_ADN.asSource();
    /** Uploaded by hand (single XML or zip) - an internal origin, not a provider. */
    public static final DocumentSourceKey UPLOAD = new DocumentSourceKey("UPLOAD");

    public DocumentSourceKey {
        Objects.requireNonNull(value, "value");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid document source key shape");
        }
    }

    public static DocumentSourceKey of(String value) {
        return new DocumentSourceKey(value);
    }

    public boolean isUpload() {
        return UPLOAD.equals(this);
    }

    @Override
    public String toString() {
        return value;
    }
}
