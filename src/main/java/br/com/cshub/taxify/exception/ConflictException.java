package br.com.cshub.taxify.exception;

import java.util.Map;

/** 409. Prefer the constructors with the i18n key: it becomes the {@code code} of the error body. */
public class ConflictException extends BusinessException {

    public static final String DEFAULT_CODE = "error.generic.conflict";

    public ConflictException() {
        this("CONFLICT!");
    }

    public ConflictException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ConflictException(String code, String message) {
        super(code, message, null, null);
    }

    /** With extra fields at the top of the error body (e.g. the trial limit: kind, limit, inUse, planName). */
    public ConflictException(String code, String message, Map<String, Object> extras) {
        super(code, message, null, extras);
    }

    public ConflictException(String message, Throwable cause) {
        super(DEFAULT_CODE, message, cause, null);
    }

    public ConflictException(Throwable cause) {
        super(DEFAULT_CODE, cause == null ? null : cause.toString(), cause, null);
    }
}
