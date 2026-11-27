package br.com.cshub.taxify.exception;

import br.com.cshub.taxify.service.firmaccess.FirmAccess;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Escrita de um escritório em somente leitura (F1-RN-08): 403 {@code error.firm.access.blocked}, com a situação do
 * escritório em {@code firmStatus} (BLOCKED, SUSPENDED, CANCELED), o {@code reason} e o {@code blockedSince} no corpo.
 */
public class FirmAccessBlockedException extends ForbiddenException {

    public static final String CODE = "error.firm.access.blocked";

    private final transient FirmAccess access;

    public FirmAccessBlockedException(String message, FirmAccess access) {
        super(CODE, message, extras(access));
        this.access = access;
    }

    public FirmAccess getAccess() {
        return access;
    }

    private static Map<String, Object> extras(FirmAccess access) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("firmStatus", access.status().name());
        extras.put("reason", access.reason() == null ? null : access.reason().name());
        extras.put("blockedSince", access.blockedSince() == null ? null : access.blockedSince().toString());
        return extras;
    }
}
