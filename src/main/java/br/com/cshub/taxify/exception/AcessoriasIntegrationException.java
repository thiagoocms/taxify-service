package br.com.cshub.taxify.exception;

public class AcessoriasIntegrationException extends RuntimeException {

    public AcessoriasIntegrationException(String message) {
        super(message);
    }

    public AcessoriasIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
