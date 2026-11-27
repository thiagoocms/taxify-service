package br.com.cshub.taxify.exception;

/** 401. */
public class UnauthorizedException extends BusinessException {

    public static final String DEFAULT_CODE = "error.generic.unauthorized";

    public UnauthorizedException() {
        this("Unauthorized");
    }

    public UnauthorizedException(String message) {
        this(DEFAULT_CODE, message);
    }

    public UnauthorizedException(String code, String message) {
        super(code, message, null, null);
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(DEFAULT_CODE, message, cause, null);
    }

    public UnauthorizedException(Throwable cause) {
        super(DEFAULT_CODE, cause == null ? null : cause.toString(), cause, null);
    }
}
