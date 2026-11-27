package br.com.cshub.taxify.exception;

/** 429 - a rate limit of ours (e.g. signups per IP per day, F1-RN-06). */
public class TooManyRequestsException extends BusinessException {

    public static final String DEFAULT_CODE = "error.generic.too.many.requests";

    public TooManyRequestsException(String message) {
        this(DEFAULT_CODE, message);
    }

    public TooManyRequestsException(String code, String message) {
        super(code, message, null, null);
    }

    /** With extra fields at the top of the error body (e.g. "Sincronizar agora": retryAt, limit). */
    public TooManyRequestsException(String code, String message, java.util.Map<String, Object> extras) {
        super(code, message, null, extras);
    }
}
