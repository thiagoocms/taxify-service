package br.com.cshub.taxify.exception;

/** 502 - the external service failed or refused the call. */
public class AcessoriasIntegrationException extends BusinessException {

    public static final String DEFAULT_CODE = "error.acessorias.integration.failed";

    public AcessoriasIntegrationException(String message) {
        this(DEFAULT_CODE, message);
    }

    public AcessoriasIntegrationException(String message, Throwable cause) {
        super(DEFAULT_CODE, message, cause, null);
    }

    public AcessoriasIntegrationException(String code, String message) {
        super(code, message, null, null);
    }

    public AcessoriasIntegrationException(String code, String message, Throwable cause) {
        super(code, message, cause, null);
    }
}
