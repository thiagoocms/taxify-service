package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.enumerated.TaxpayerDocumentType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Issuer or recipient of a fiscal document: document and its type, name, UF and IBGE city code. The columns get the
 * prefix of the role ({@code issuer_*}, {@code recipient_*}) by {@code @AttributeOverrides} in the document.
 */
@Embeddable
public record Party(
        String document,
        @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) TaxpayerDocumentType documentType,
        String name,
        String uf,
        String cityCode
) implements PartyIdentity {

    /** A party identified by a Brazilian document, the type taken from its size (14 = CNPJ, 11 = CPF). */
    public static Party brazilian(String document, String name, String uf, String cityCode) {
        return new Party(document, TaxpayerDocumentType.bySize(document), name, uf, cityCode);
    }
}
