package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A CT-e participant other than issuer and recipient (sender, shipper, receiver, taker): only document, type and name
 * have columns ({@code sender_*}, {@code shipper_*}, {@code receiver_*}, {@code taker_*}, spec 3.4 block T). The UF
 * exists only for sender and taker and is kept in the {@link Cte} itself.
 */
@Embeddable
public record CteParty(
        String document,
        @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) TaxpayerDocumentType documentType,
        String name
) implements PartyIdentity {

    public static CteParty brazilian(String document, String name) {
        return new CteParty(document, TaxpayerDocumentType.bySize(document), name);
    }
}
