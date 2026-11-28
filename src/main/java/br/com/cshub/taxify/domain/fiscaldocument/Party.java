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

    /** No party (every column null) - what the getters of the document give instead of null. */
    public static final Party EMPTY = new Party(null, null, null, null, null);

    /**
     * The first of {@code cnpj}, {@code cpf} and {@code foreignId} with text (FD-RN-09, FD-RN-55: blank is no document),
     * typed accordingly; no document and no type when none has text.
     */
    public static Party of(String cnpj, String cpf, String foreignId, String name, String uf, String cityCode) {
        if (FiscalText.hasText(cnpj)) {
            return new Party(cnpj, TaxpayerDocumentType.CNPJ, name, uf, cityCode);
        }
        if (FiscalText.hasText(cpf)) {
            return new Party(cpf, TaxpayerDocumentType.CPF, name, uf, cityCode);
        }
        if (FiscalText.hasText(foreignId)) {
            return new Party(foreignId, TaxpayerDocumentType.FOREIGN, name, uf, cityCode);
        }
        return new Party(null, null, name, uf, cityCode);
    }

    /** A party identified by a Brazilian document, the type taken from its size (14 = CNPJ, 11 = CPF). */
    public static Party brazilian(String document, String name, String uf, String cityCode) {
        return new Party(document, TaxpayerDocumentType.bySize(document), name, uf, cityCode);
    }
}
