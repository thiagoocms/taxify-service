package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * Unit, quantity and unit price of an item, commercial ({@code commercial_*}) or taxable ({@code taxable_*}) - the
 * prefix comes from {@code @AttributeOverrides} in {@link FiscalDocumentItem}. Quantities are not money nor rates:
 * they are kept as they come (column {@code decimal(19,4)}, as today).
 */
@Embeddable
public record TradeUnit(
        String unit,
        BigDecimal quantity,
        BigDecimal unitPrice
) {
}
