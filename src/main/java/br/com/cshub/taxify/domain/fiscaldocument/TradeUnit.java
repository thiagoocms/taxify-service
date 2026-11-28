package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Unit, quantity and unit price of an item, commercial ({@code commercial_*}) or taxable ({@code taxable_*}) - the
 * prefix comes from {@code @AttributeOverrides} in {@link FiscalDocumentItem}. Quantities and unit prices are not money
 * nor rates (I-17 does not cover them): the layout allows up to 10 decimal places in {@code qCom}/{@code vUnCom}, and
 * the column is {@code decimal(19,4)}. The domain rounds explicitly to 4 places with {@link RoundingMode#HALF_UP} - the
 * same result MySQL gives today for positive values - instead of depending on the database (spec 3.3, D-07).
 */
@Embeddable
public record TradeUnit(
        String unit,
        BigDecimal quantity,
        BigDecimal unitPrice
) {
    public static final int SCALE = 4;
    /** No unit, quantity nor price (every column null). */
    public static final TradeUnit EMPTY = new TradeUnit(null, null, null);

    public TradeUnit {
        quantity = quantity == null ? null : quantity.setScale(SCALE, RoundingMode.HALF_UP);
        unitPrice = unitPrice == null ? null : unitPrice.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
