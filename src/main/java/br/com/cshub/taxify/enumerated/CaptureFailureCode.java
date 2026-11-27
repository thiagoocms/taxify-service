package br.com.cshub.taxify.enumerated;

/**
 * A lista fechada de códigos de falha e aviso da captura (CP-RN-11). O código é escolhido pelo tipo do erro (código da
 * exceção, status HTTP, cStat, classe da causa) e nunca pelo texto da mensagem; a mensagem mostrada vem do i18n
 * ({@code capture.failure.<código>}), com no máximo o status, a contagem ou uma data lida de outra tabela.
 */
public enum CaptureFailureCode {
    SIEG_API_KEY_MISSING(Kind.FAILURE),
    SIEG_AUTH(Kind.FAILURE),
    SIEG_RATE_LIMITED(Kind.FAILURE),
    SIEG_REJECTED(Kind.FAILURE),
    SIEG_UNAVAILABLE(Kind.FAILURE),
    SIEG_TIMEOUT(Kind.FAILURE),
    SIEG_CONNECTION(Kind.FAILURE),
    SIEG_INVALID_RESPONSE(Kind.FAILURE),
    SIEG_COUNT_FAILED(Kind.WARNING),
    XML_PARSE_ERRORS(Kind.WARNING),
    /** A piece of the blocked-months recovery failed too many times and was given up (not retried). */
    SIEG_RECOVERY_GAVE_UP(Kind.WARNING),
    XML_STORAGE_FAILED(Kind.FAILURE),
    COMPANY_WITHOUT_CNPJ(Kind.FAILURE),
    COMPANY_WITHOUT_UF(Kind.FAILURE),
    CERT_MISSING(Kind.FAILURE),
    CERT_EXPIRED(Kind.FAILURE),
    CERT_FILE_MISSING(Kind.FAILURE),
    CERT_INVALID(Kind.FAILURE),
    SEFAZ_REJECTED(Kind.FAILURE),
    SEFAZ_CONSUMO_INDEVIDO(Kind.WARNING),
    SEFAZ_TIMEOUT(Kind.FAILURE),
    SEFAZ_CONNECTION(Kind.FAILURE),
    ADN_REJECTED(Kind.FAILURE),
    ADN_TIMEOUT(Kind.FAILURE),
    ADN_CONNECTION(Kind.FAILURE),
    INTERNAL_ERROR(Kind.FAILURE),
    /** Só leitura: SEFAZ ou ADN com ERRO gravado antes do painel, sem linha de etapa (CP-RN-07). */
    LEGACY_ERROR(Kind.FAILURE);

    public enum Kind {
        FAILURE, WARNING
    }

    private final Kind kind;

    CaptureFailureCode(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Chave do i18n da mensagem. */
    public String messageKey() {
        return "capture.failure." + name();
    }

    /** O código gravado no banco, ou null quando não é um da lista (linha antiga ou adulterada). */
    public static CaptureFailureCode parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
