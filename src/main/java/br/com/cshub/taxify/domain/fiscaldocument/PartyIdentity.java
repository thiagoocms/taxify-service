package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;

/** One document and one type per party of a fiscal document (FD-RN-09). */
public interface PartyIdentity {

    String document();

    TaxpayerDocumentType documentType();

    /**
     * I-06: no document and no type, or a type whose shape the document has (CNPJ = 14 digits, CPF = 11 digits,
     * FOREIGN = up to 20 characters). A document without a type, or a type without a document, is incoherent.
     */
    default boolean hasCoherentDocument() {
        boolean noDocument = !FiscalText.hasText(document());
        if (documentType() == null) {
            return noDocument;
        }
        return !noDocument && documentType().accepts(document());
    }

    /** The document when it is a CNPJ - what the API shows in the {@code *Cnpj} fields. */
    default String cnpj() {
        return documentOf(TaxpayerDocumentType.CNPJ);
    }

    /** The document when it is a CPF. */
    default String cpf() {
        return documentOf(TaxpayerDocumentType.CPF);
    }

    /** The document when it is a foreign id (idEstrangeiro). */
    default String foreignId() {
        return documentOf(TaxpayerDocumentType.FOREIGN);
    }

    private String documentOf(TaxpayerDocumentType type) {
        return documentType() == type ? document() : null;
    }
}
