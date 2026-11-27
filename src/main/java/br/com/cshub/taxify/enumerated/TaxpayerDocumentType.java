package br.com.cshub.taxify.enumerated;

/** The kind of document that identifies a party of a fiscal document (FD-RN-09, I-06). */
public enum TaxpayerDocumentType {
    CNPJ,
    CPF,
    /** idEstrangeiro of the NF-e: up to 20 characters, not necessarily digits. */
    FOREIGN;

    public static final int CNPJ_LENGTH = 14;
    public static final int CPF_LENGTH = 11;
    public static final int FOREIGN_MAX_LENGTH = 20;

    /**
     * The type a Brazilian document has by its size (what the NFS-e, which keeps a single document column, implies):
     * 14 digits = CNPJ, 11 digits = CPF; anything else (or null/blank) = null - never guessed as FOREIGN.
     */
    public static TaxpayerDocumentType bySize(String document) {
        if (document == null || !isDigits(document)) {
            return null;
        }
        return switch (document.length()) {
            case CNPJ_LENGTH -> CNPJ;
            case CPF_LENGTH -> CPF;
            default -> null;
        };
    }

    /** Whether {@code document} has the shape this type requires (I-06). */
    public boolean accepts(String document) {
        if (document == null || document.isBlank()) {
            return false;
        }
        return switch (this) {
            case CNPJ -> document.length() == CNPJ_LENGTH && isDigits(document);
            case CPF -> document.length() == CPF_LENGTH && isDigits(document);
            case FOREIGN -> document.length() <= FOREIGN_MAX_LENGTH;
        };
    }

    private static boolean isDigits(String value) {
        return !value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9');
    }
}
