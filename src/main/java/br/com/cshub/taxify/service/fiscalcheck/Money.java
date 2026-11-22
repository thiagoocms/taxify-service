package br.com.cshub.taxify.service.fiscalcheck;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Small helpers the value checks share. */
final class Money {

    /** One cent - rounding item by item may leave this much. */
    static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    /** base x rate% x (1 - reduction%), rounded to cents. */
    static BigDecimal tax(BigDecimal base, BigDecimal ratePercent, BigDecimal reductionPercent) {
        BigDecimal rate = ratePercent;
        if (reductionPercent != null && reductionPercent.signum() != 0) {
            rate = rate.multiply(HUNDRED.subtract(reductionPercent)).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        }
        return base.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    static boolean differs(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(TOLERANCE) > 0;
    }

    static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static String show(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
