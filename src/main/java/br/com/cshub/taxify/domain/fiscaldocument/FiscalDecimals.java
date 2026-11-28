package br.com.cshub.taxify.domain.fiscaldocument;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Precision of the values of a fiscal document (FD-RN-11, I-17): money in {@code decimal(19,2)} and rates and
 * percentages in {@code decimal(9,4)}. A value that fits keeps exactly what came; one with more decimal places is
 * rounded {@link RoundingMode#HALF_UP} to the column's scale - what MySQL did when the old columns received it -
 * and recorded in {@link ImportAdjustments} (decision of 2026-11-28: no note is lost, every rounding is logged).
 */
public final class FiscalDecimals {

    public static final int MONEY_SCALE = 2;
    public static final int RATE_SCALE = 4;

    private FiscalDecimals() {
    }

    /** Money with scale 2, or null; {@code field} names it in the rounding record ({@code Type.field}). */
    public static BigDecimal money(BigDecimal value, String field) {
        return scaled(value, MONEY_SCALE, field);
    }

    /**
     * Rate or percentage with scale 4, or null. Quantities and unit prices of an item are not rates: I-17 does not
     * cover them (vUnCom may have up to 10 decimal places); {@link TradeUnit} rounds them to 4 places (D-07).
     */
    public static BigDecimal rate(BigDecimal value, String field) {
        return scaled(value, RATE_SCALE, field);
    }

    private static BigDecimal scaled(BigDecimal value, int scale, String field) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= scale) {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        }
        ImportAdjustments.record(ImportAdjustments.Kind.DECIMAL_ROUNDED, field, stripped.scale());
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
}
