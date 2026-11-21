package br.com.cshub.taxify.dto.assessment;

import java.math.BigDecimal;

/** One estimated tax of the period. {@code amount} null = not estimable from notes (see {@code note}). */
public record TaxEstimateDTO(
        String code,
        String label,
        BigDecimal base,
        /** Percent, e.g. 6.5432 for 6,5432%. */
        BigDecimal rate,
        BigDecimal amount,
        String note
) {
}
