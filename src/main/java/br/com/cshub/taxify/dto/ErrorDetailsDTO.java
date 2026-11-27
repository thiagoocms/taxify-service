package br.com.cshub.taxify.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Map;

/**
 * The one error body of the API: {@code {timestamp, status, code, message, details}}, plus the {@code extras} of the
 * error at the top level when it has them (e.g. {@code kind, limit, inUse, planName} on the trial limit;
 * {@code firmStatus, reason, blockedSince} on the read-only firm). {@code status} is always the HTTP status and
 * {@code code} the i18n key of the message.
 */
@JsonPropertyOrder({"timestamp", "status", "code", "message", "details"})
public record ErrorDetailsDTO(Long timestamp, Integer status, String code, String message, String details,
                              @JsonIgnore Map<String, Object> extras) {

    public ErrorDetailsDTO(Long timestamp, Integer status, String code, String message, String details) {
        this(timestamp, status, code, message, details, Map.of());
    }

    /** Serialized at the top level of the body, after the common fields. */
    @JsonAnyGetter
    public Map<String, Object> extraFields() {
        return extras == null ? Map.of() : extras;
    }
}
