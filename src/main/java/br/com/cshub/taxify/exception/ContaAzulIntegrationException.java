package br.com.cshub.taxify.exception;

public class ContaAzulIntegrationException extends RuntimeException {

    public ContaAzulIntegrationException(String message) {
        super(message);
    }

    public ContaAzulIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
