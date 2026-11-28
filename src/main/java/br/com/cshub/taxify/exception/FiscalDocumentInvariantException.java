package br.com.cshub.taxify.exception;

import br.com.cshub.taxify.enumerated.FiscalDocumentInvariant;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;

/**
 * A fiscal document broke one of its invariants (spec 3.3, FD-RN-03). Internal: it never reaches an error body with
 * its own text - the upload counts it as a file with error and the capture as a read failure. The message carries only
 * the invariant code and the document type (no key, CNPJ, name or value).
 */
public class FiscalDocumentInvariantException extends RuntimeException {

    private final transient FiscalDocumentInvariant invariant;
    private final transient FiscalDocumentType documentType;

    public FiscalDocumentInvariantException(FiscalDocumentInvariant invariant, FiscalDocumentType documentType) {
        super("fiscal.document.invariant.violated invariant=" + invariant + " type=" + documentType);
        this.invariant = invariant;
        this.documentType = documentType;
    }

    public FiscalDocumentInvariant getInvariant() {
        return invariant;
    }

    /** Null when the violation was found while building a value, before the document existed. */
    public FiscalDocumentType getDocumentType() {
        return documentType;
    }
}
