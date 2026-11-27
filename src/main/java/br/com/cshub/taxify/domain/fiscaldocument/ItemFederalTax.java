package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/**
 * IPI, PIS or COFINS of an item: CST, base, rate and value. The prefix ({@code ipi_}, {@code pis_}, {@code cofins_})
 * comes from {@code @AttributeOverrides} in {@link FiscalDocumentItem}.
 */
@Embeddable
public record ItemFederalTax(
        String cst,
        BigDecimal base,
        BigDecimal rate,
        BigDecimal amount
) {
    public ItemFederalTax {
        base = money(base);
        rate = FiscalDecimals.rate(rate);
        amount = money(amount);
    }
}
