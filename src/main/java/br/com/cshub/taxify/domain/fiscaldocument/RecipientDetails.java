package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/** What a goods invoice says about its recipient beyond the {@link Party} (spec 3.4, block G). */
@Embeddable
public record RecipientDetails(
        @Column(name = "recipient_state_registration", length = 20) String stateRegistration,
        @Column(name = "recipient_state_registration_indicator", length = 5) String stateRegistrationIndicator,
        @Column(name = "recipient_email", length = 200) String email,
        @Embedded
        @AttributeOverrides({
                @AttributeOverride(name = "street", column = @Column(name = "recipient_address_street", length = 200)),
                @AttributeOverride(name = "number", column = @Column(name = "recipient_address_number", length = 20)),
                @AttributeOverride(name = "complement", column = @Column(name = "recipient_address_complement", length = 100)),
                @AttributeOverride(name = "district", column = @Column(name = "recipient_address_district", length = 100)),
                @AttributeOverride(name = "cityName", column = @Column(name = "recipient_address_city_name", length = 100)),
                @AttributeOverride(name = "zipCode", column = @Column(name = "recipient_address_zip_code", length = 10)),
                @AttributeOverride(name = "phone", column = @Column(name = "recipient_address_phone", length = 20)),
                @AttributeOverride(name = "countryCode", column = @Column(name = "recipient_address_country_code", length = 10)),
                @AttributeOverride(name = "countryName", column = @Column(name = "recipient_address_country_name", length = 60))
        })
        PartyAddress address
) {
}
