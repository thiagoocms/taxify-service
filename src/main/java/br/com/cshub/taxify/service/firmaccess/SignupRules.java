package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.common.DocumentNumbers;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Regras puras (sem I/O) dos dados do escritório (F1-RN-02, F1-RN-11): o dígito verificador do CNPJ, o telefone, o
 * e-mail e a trava do cadastro. Quem responde ao pedido com o erro é a validação (SignupValidation, PlatformFirmValidation).
 */
public final class SignupRules {

    /** A versão de termos que mantém o cadastro fechado em produção (F1-RN-11). */
    public static final String DRAFT_TERMS_PREFIX = "RASCUNHO";
    public static final int EMAIL_MAX = 300;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private SignupRules() {
    }

    /**
     * CNPJ com 14 dígitos e os dois dígitos verificadores certos (módulo 11). Aceita com ou sem máscara; uma
     * sequência repetida (00000000000000, 11111111111111...) não vale.
     */
    public static boolean isValidCnpj(String value) {
        String digits = digits(value);
        if (digits == null || digits.length() != 14 || digits.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigit(digits, 12) == digits.charAt(12) - '0' && checkDigit(digits, 13) == digits.charAt(13) - '0';
    }

    private static int checkDigit(String digits, int length) {
        int[] weights = length == 12
                ? new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2}
                : new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (digits.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    /** Telefone/WhatsApp só com dígitos: DDD + número, 10 ou 11 dígitos; fora disso, nulo (inválido). */
    public static String normalizePhone(String value) {
        String digits = digits(value);
        return digits != null && (digits.length() == 10 || digits.length() == 11) ? digits : null;
    }

    public static boolean isValidEmail(String value) {
        return value != null && value.length() <= EMAIL_MAX && EMAIL.matcher(value.strip()).matches();
    }

    /** Cadastro aberto só com a chave ligada e uma versão real dos termos (nem vazia, nem RASCUNHO...). */
    public static boolean signupAvailable(boolean enabled, String termsVersion) {
        return enabled && termsVersion != null && !termsVersion.isBlank()
                && !termsVersion.strip().toUpperCase(Locale.ROOT).startsWith(DRAFT_TERMS_PREFIX);
    }

    private static String digits(String value) {
        return DocumentNumbers.onlyDigits(value);
    }
}
