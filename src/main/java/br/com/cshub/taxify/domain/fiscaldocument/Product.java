package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The product of an item ({@code det/prod}). The CFOP is stored without surrounding spaces (only the ordinary space,
 * as SQL {@code TRIM}, {@link FiscalText}), so the queries compare it
 * directly and use the index (no {@code trim(cfop)} any more, spec 3.6).
 */
@Embeddable
public record Product(
        @Column(name = "product_code", length = 60) String code,
        @Column(name = "barcode", length = 20) String barcode,
        @Column(name = "description", length = 500) String description,
        @Column(name = "ncm", length = 10) String ncm,
        @Column(name = "cest", length = 10) String cest,
        /** cBenef - benefício fiscal code of the state. */
        @Column(name = "benefit_code", length = 10) String benefitCode,
        @Column(name = "cfop", length = 10) String cfop
) {
    /** No product field (every column null). */
    public static final Product EMPTY = new Product(null, null, null, null, null, null, null);

    public Product {
        cfop = FiscalText.trimSpaces(cfop);
    }
}
