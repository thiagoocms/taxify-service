package br.com.cshub.taxify.mapper.fiscaldocument;

import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.FiscalDirection;

import java.util.Arrays;

/**
 * The NFS-e direction of the API and of the old table ({@code PRESTADA}, {@code TOMADA}, {@code TERCEIRO}) as the
 * (direction, role) pair of the new model, and back (FD-RN-08, FD-RN-23). The translation lives in the mapper, never in
 * the domain: the JSON keeps {@code direction = "TOMADA"} while the model has {@code TAKER} + {@code INBOUND}.
 */
public enum LegacyNfseDirection {
    PRESTADA(FiscalDirection.OUTBOUND, CompanyDocumentRole.ISSUER),
    TOMADA(FiscalDirection.INBOUND, CompanyDocumentRole.TAKER),
    TERCEIRO(null, CompanyDocumentRole.THIRD_PARTY);

    private final FiscalDirection direction;
    private final CompanyDocumentRole role;

    LegacyNfseDirection(FiscalDirection direction, CompanyDocumentRole role) {
        this.direction = direction;
        this.role = role;
    }

    public FiscalDirection direction() {
        return direction;
    }

    public CompanyDocumentRole role() {
        return role;
    }

    /** The pair of an old direction; an unknown value is an error (it never existed in the table). */
    public static LegacyNfseDirection of(String legacyDirection) {
        return Arrays.stream(values())
                .filter(value -> value.name().equals(legacyDirection))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown legacy NFS-e direction"));
    }

    /** The old direction an NFS-e role shows in the API. */
    public static LegacyNfseDirection ofRole(CompanyDocumentRole role) {
        return Arrays.stream(values())
                .filter(value -> value.role == role)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("role without legacy NFS-e direction"));
    }
}
