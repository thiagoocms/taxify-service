package br.com.cshub.taxify.exception;

/** 502 - the external service failed or refused the call. */
public class SiegIntegrationException extends BusinessException {

    public static final String DEFAULT_CODE = "error.sieg.integration.failed";

    public SiegIntegrationException(String message) {
        this(DEFAULT_CODE, message);
    }

    public SiegIntegrationException(String message, Throwable cause) {
        super(DEFAULT_CODE, message, cause, null);
    }

    public SiegIntegrationException(String code, String message) {
        super(code, message, null, null);
    }

    public SiegIntegrationException(String code, String message, Throwable cause) {
        super(code, message, cause, null);
    }
}
