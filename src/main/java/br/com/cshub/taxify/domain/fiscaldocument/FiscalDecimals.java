package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.enumerated.FiscalDocumentInvariant;
import br.com.cshub.taxify.exception.FiscalDocumentInvariantException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Precision of the values of a fiscal document (FD-RN-11, I-17): money in {@code decimal(19,2)} and rates and
 * percentages in {@code decimal(9,4)}. A value is kept exactly as it came: it only gets the column scale when that
 * needs no rounding ({@link RoundingMode#UNNECESSARY}); a value with more decimal places is refused, never rounded.
 */
public final class FiscalDecimals {

    public static final int MONEY_SCALE = 2;
    public static final int RATE_SCALE = 4;

    private FiscalDecimals() {
    }

    /** Money with scale 2, or null. */
    public static BigDecimal money(BigDecimal value) {
        return exact(value, MONEY_SCALE);
    }

    /**
     * Rate or percentage with scale 4, or null. Quantities and unit prices of an item are not rates: I-17 does not
     * cover them (vUnCom may have up to 10 decimal places), so they are kept as they come.
     */
    public static BigDecimal rate(BigDecimal value) {
        return exact(value, RATE_SCALE);
    }

    private static BigDecimal exact(BigDecimal value, int scale) {
        if (value == null) {
            return null;
        }
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException moreDecimalPlaces) {
            throw new FiscalDocumentInvariantException(FiscalDocumentInvariant.I17_DECIMAL_SCALE, null);
        }
    }
}
