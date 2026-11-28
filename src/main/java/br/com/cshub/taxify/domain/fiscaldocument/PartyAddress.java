package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Embeddable;

/** Address of the issuer or of the recipient of a goods invoice ({@code issuer_address_*}, {@code recipient_address_*}). */
@Embeddable
public record PartyAddress(
        String street,
        String number,
        String complement,
        String district,
        String cityName,
        String zipCode,
        String phone,
        String countryCode,
        String countryName
) {
    /** No address (every column null). */
    public static final PartyAddress EMPTY = new PartyAddress(null, null, null, null, null, null, null, null, null);
}
