package br.com.cshub.taxify.exception;

import java.util.Map;

/**
 * 403 of a business rule (e.g. the e-mail not verified yet, the read-only firm). A missing permission stays Spring's
 * AccessDeniedException (@RequiresPermission / @AdminOnly / @PlatformAdminOnly).
 */
public class ForbiddenException extends BusinessException {

    public static final String DEFAULT_CODE = "error.access.denied";

    public ForbiddenException(String message) {
        this(DEFAULT_CODE, message);
    }

    public ForbiddenException(String code, String message) {
        super(code, message, null, null);
    }

    public ForbiddenException(String code, String message, Map<String, Object> extras) {
        super(code, message, null, extras);
    }
}
