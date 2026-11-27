package br.com.cshub.taxify.exception;

import java.util.Map;

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

    /** With extra fields at the top of the error body (e.g. {@code invalidField}). */
    public BadRequestException(String code, String message, Map<String, Object> extras) {
        super(code, message, null, extras);
    }

    public BadRequestException(String message, Throwable cause) {
        super(DEFAULT_CODE, message, cause, null);
    }

    public BadRequestException(Throwable cause) {
        super(DEFAULT_CODE, cause == null ? null : cause.toString(), cause, null);
    }
}
