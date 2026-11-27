package br.com.cshub.taxify.exception;

/** 502 - the external service failed or refused the call. */
public class ContaAzulIntegrationException extends BusinessException {

    public static final String DEFAULT_CODE = "error.contaAzul.integration.failed";

    public ContaAzulIntegrationException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ContaAzulIntegrationException(String message, Throwable cause) {
        super(DEFAULT_CODE, message, cause, null);
    }

    public ContaAzulIntegrationException(String code, String message) {
        super(code, message, null, null);
    }

    public ContaAzulIntegrationException(String code, String message, Throwable cause) {
        super(code, message, cause, null);
    }
}
