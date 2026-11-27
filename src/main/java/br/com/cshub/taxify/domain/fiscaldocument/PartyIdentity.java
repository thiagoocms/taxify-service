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
        boolean noDocument = document() == null || document().isBlank();
        if (documentType() == null) {
            return noDocument;
        }
        return documentType().accepts(document());
    }
}
