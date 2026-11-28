package br.com.cshub.taxify.enumerated;

/**
 * The subtype of a fiscal document - the values are exactly the ones of the {@code document_type} discriminator of
 * {@code fiscal_documents} (FD-RN-01/02). A new value needs a migration (the table has a {@code CHECK}).
 */
public enum FiscalDocumentType {
    NFE,
    NFCE,
    NFSE,
    CTE
}
