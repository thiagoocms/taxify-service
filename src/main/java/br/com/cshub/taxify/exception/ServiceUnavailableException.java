package br.com.cshub.taxify.exception;

/** 503 - a feature switched off on purpose (e.g. the public signup closed in production, F1-RN-11). */
public class ServiceUnavailableException extends BusinessException {

    public static final String DEFAULT_CODE = "error.generic.unavailable";

    public ServiceUnavailableException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ServiceUnavailableException(String code, String message) {
        super(code, message, null, null);
    }
}
