package br.com.cshub.taxify.dto.fiscalxml;

import java.math.BigDecimal;

/** Period totals of a company's notes, entries and exits apart - the cards above the list. */
public record FiscalXmlTotalsDTO(DirectionTotals inbound, DirectionTotals outbound) {

    public record DirectionTotals(
            long count,
            BigDecimal total,
            BigDecimal icms,
            BigDecimal icmsSt,
            BigDecimal ipi,
            BigDecimal pis,
            BigDecimal cofins
    ) {
        public static DirectionTotals empty() {
            return new DirectionTotals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }
}
