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
}
