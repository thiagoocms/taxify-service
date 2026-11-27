package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/** What a goods invoice says about its issuer beyond the {@link Party} (spec 3.4, block G). */
@Embeddable
public record IssuerDetails(
        @Column(name = "issuer_trade_name", length = 200) String tradeName,
        @Column(name = "issuer_state_registration", length = 20) String stateRegistration,
        @Column(name = "issuer_st_state_registration", length = 20) String stStateRegistration,
        @Column(name = "issuer_municipal_registration", length = 20) String municipalRegistration,
        @Column(name = "issuer_cnae", length = 10) String cnae,
        @Column(name = "issuer_tax_regime_code", length = 5) String taxRegimeCode,
        @Embedded
        @AttributeOverrides({
                @AttributeOverride(name = "street", column = @Column(name = "issuer_address_street", length = 200)),
                @AttributeOverride(name = "number", column = @Column(name = "issuer_address_number", length = 20)),
                @AttributeOverride(name = "complement", column = @Column(name = "issuer_address_complement", length = 100)),
                @AttributeOverride(name = "district", column = @Column(name = "issuer_address_district", length = 100)),
                @AttributeOverride(name = "cityName", column = @Column(name = "issuer_address_city_name", length = 100)),
                @AttributeOverride(name = "zipCode", column = @Column(name = "issuer_address_zip_code", length = 10)),
                @AttributeOverride(name = "phone", column = @Column(name = "issuer_address_phone", length = 20)),
                @AttributeOverride(name = "countryCode", column = @Column(name = "issuer_address_country_code", length = 10)),
                @AttributeOverride(name = "countryName", column = @Column(name = "issuer_address_country_name", length = 60))
        })
        PartyAddress address
) {
}
