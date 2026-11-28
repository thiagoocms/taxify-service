package br.com.cshub.taxify.domain.fiscaldocument;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Key of an XML provider (FD-RN-40): an open value, not a closed enum, so a new provider needs no migration and no
 * enum change. Stored as {@code varchar(30)} ({@code capture_period_counts.channel}). Only the shape is checked here
 * (upper case letters, digits and underscore, starting with a letter, up to 30 characters); whether the provider is
 * registered is checked by the capture core against the registered providers (fase P).
 */
public record ProviderKey(String value) {

    public static final int MAX_LENGTH = 30;
    private static final Pattern SHAPE = Pattern.compile("[A-Z][A-Z0-9_]{0," + (MAX_LENGTH - 1) + "}");

    public static final ProviderKey SIEG = new ProviderKey("SIEG");
    /** NFeDistribuicaoDFe of the Ambiente Nacional (was {@code SEFAZ_DIST}). */
    public static final ProviderKey SEFAZ_DFE = new ProviderKey("SEFAZ_DFE");
    /** Ambiente Nacional da NFS-e (was {@code ADN}). */
    public static final ProviderKey NFSE_ADN = new ProviderKey("NFSE_ADN");

    public ProviderKey {
        Objects.requireNonNull(value, "value");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid provider key shape");
        }
    }

    public static ProviderKey of(String value) {
        return new ProviderKey(value);
    }

    /** The origin a document delivered by this provider gets in {@code fiscal_document_sources}. */
    public DocumentSourceKey asSource() {
        return new DocumentSourceKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
