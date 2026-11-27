package br.com.cshub.taxify.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base of the business exceptions that become an HTTP error (see GlobalExceptionHandler). Each one carries the i18n
 * key of its message - the {@code code} of the error body, stable for the front to decide what to show (e.g.
 * {@code error.signup.firm.document.invalid}) - and, when the error needs them, extra fields that go at the top of the
 * body next to the common ones ({@code kind}, {@code limit}, {@code inUse}, {@code planName} on the trial limit;
 * {@code firmStatus}, {@code reason} and {@code blockedSince} on the read-only firm).
 */
public abstract class BusinessException extends RuntimeException {

    private final String code;
    private final Map<String, Object> extras;

    protected BusinessException(String code, String message, Throwable cause, Map<String, Object> extras) {
        super(message, cause);
        this.code = code;
        // LinkedHashMap: keeps the order of the body and accepts null values (e.g. no blockedSince)
        this.extras = extras == null || extras.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
    }

    /** The i18n key of the message (errors_*.properties). */
    public String getCode() {
        return code;
    }

    /** Extra fields of the error body; empty for most errors. */
    public Map<String, Object> getExtras() {
        return extras;
    }
}
