package br.com.cshub.taxify.exception;

/** 400. Prefer the constructor with the i18n key: it becomes the {@code code} of the error body. */
public class BadRequestException extends BusinessException {

    public static final String DEFAULT_CODE = "error.generic.bad.request";

    public BadRequestException() {
        this("Bad Request!");
    }

    public BadRequestException(String message) {
        this(DEFAULT_CODE, message);
    }

    public BadRequestException(String code, String message) {
        super(code, message, null, null);
    }

    public BadRequestException(String message, Throwable cause) {
        super(DEFAULT_CODE, message, cause, null);
    }

    public BadRequestException(Throwable cause) {
        super(DEFAULT_CODE, cause == null ? null : cause.toString(), cause, null);
    }
}
