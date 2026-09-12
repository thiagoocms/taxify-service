package br.com.cshub.taxify.exception;

public class SiegIntegrationException extends RuntimeException {

    public SiegIntegrationException(String message) {
        super(message);
    }

    public SiegIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
