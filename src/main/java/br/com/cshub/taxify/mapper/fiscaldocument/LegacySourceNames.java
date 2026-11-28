package br.com.cshub.taxify.mapper.fiscaldocument;

import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;

/**
 * The source names of the API and of the old tables that changed in the new model (spec 3.10): {@code SEFAZ_DIST} is
 * {@code SEFAZ_DFE} and {@code ADN} is {@code NFSE_ADN}; every other name is the same. The API keeps the old names
 * (FD-RN-23, CT-FD-31) - e.g. the {@code origem} filter of the XML list.
 */
public final class LegacySourceNames {

    public static final String SEFAZ_DIST = "SEFAZ_DIST";
    public static final String ADN = "ADN";

    private LegacySourceNames() {
    }

    /** Old name to the source of the model. */
    public static DocumentSourceKey toSource(String legacyName) {
        if (SEFAZ_DIST.equals(legacyName)) {
            return DocumentSourceKey.SEFAZ_DFE;
        }
        if (ADN.equals(legacyName)) {
            return DocumentSourceKey.NFSE_ADN;
        }
        return DocumentSourceKey.of(legacyName);
    }

    /** Source of the model to the name the API shows. */
    public static String toLegacyName(DocumentSourceKey source) {
        if (DocumentSourceKey.SEFAZ_DFE.equals(source)) {
            return SEFAZ_DIST;
        }
        if (DocumentSourceKey.NFSE_ADN.equals(source)) {
            return ADN;
        }
        return source.value();
    }
}
