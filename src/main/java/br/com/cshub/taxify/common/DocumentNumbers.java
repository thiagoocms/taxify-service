package br.com.cshub.taxify.common;

/**
 * CNPJ / CPF helpers shared by every integration (SIEG, Acessórias, Conta Azul) and the
 * DANFE: Taxify stores and compares documents as digits only, and shows them masked.
 */
public final class DocumentNumbers {

    private static final int CNPJ_LENGTH = 14;
    private static final int CPF_LENGTH = 11;

    private DocumentNumbers() {
    }

    /** "11.594.485/0001-05" → "11594485000105"; null stays null. */
    public static String onlyDigits(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }

    /**
     * Masks a CNPJ (00.000.000/0000-00) or CPF (000.000.000-00), with or without the mask
     * already; anything else (foreign ids, partial numbers) comes back unchanged.
     */
    public static String format(String value) {
        String digits = onlyDigits(value);
        if (digits == null) {
            return null;
        }
        if (digits.length() == CNPJ_LENGTH) {
            return digits.replaceFirst("(\\d{2})(\\d{3})(\\d{3})(\\d{4})(\\d{2})", "$1.$2.$3/$4-$5");
        }
        if (digits.length() == CPF_LENGTH) {
            return digits.replaceFirst("(\\d{3})(\\d{3})(\\d{3})(\\d{2})", "$1.$2.$3-$4");
        }
        return value;
    }
}
