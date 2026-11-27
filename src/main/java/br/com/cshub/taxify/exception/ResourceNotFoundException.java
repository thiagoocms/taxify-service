package br.com.cshub.taxify.exception;

/** 404 - also what another tenant's record answers (never 403). */
public class ResourceNotFoundException extends BusinessException {

    public static final String DEFAULT_CODE = "error.entity.not.found";

    public ResourceNotFoundException() {
        this("Resource Not Found");
    }

    public ResourceNotFoundException(String msg) {
        this(DEFAULT_CODE, msg);
    }

    public ResourceNotFoundException(String code, String message) {
        super(code, message, null, null);
    }
}
