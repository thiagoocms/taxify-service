package br.com.cshub.taxify.exception;


/**
 * Erro da integração com o SIEG. {@link #getHttpStatus()} é o status HTTP da resposta, quando houve resposta (null em
 * falha de rede, chave ausente, pacote ilegível...) - é ele que separa os códigos do painel da captura (CP-RN-11).
 * Nunca carrega o corpo da resposta.
 */
public class SiegIntegrationException extends BusinessException {

    public static final String DEFAULT_CODE = "error.sieg.integration.failed";

    private final Integer httpStatus;

    public SiegIntegrationException(String message) {
        this(DEFAULT_CODE, message);
    }

    public SiegIntegrationException(String message, Throwable cause) {
        super(DEFAULT_CODE, message, cause, null);
        this.httpStatus = null;
    }

    public SiegIntegrationException(String code, String message) {
        super(code, message, null, null);
        this.httpStatus = null;
    }

    public SiegIntegrationException(String code, String message, Throwable cause) {
        super(code, message, cause, null);
        this.httpStatus = null;
    }

    /** A resposta do SIEG com status de erro ({@code httpStatus} &gt;= 400). */
    public SiegIntegrationException(String code, String message, int httpStatus) {
        super(code, message, null, null);
        this.httpStatus = httpStatus;
    }

    /** O status HTTP da resposta de erro do SIEG; null quando não houve resposta. */
    public Integer getHttpStatus() {
        return httpStatus;
    }
}
