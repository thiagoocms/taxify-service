package br.com.cshub.taxify.dto.assessment;

import java.math.BigDecimal;

/**
 * One month of a company's revenue from its notes (item values, canceled notes out).
 * {@code net} = sales of own production + resales + services − sales returns.
 */
public record MonthRevenueDTO(
        String month,
        BigDecimal ownProductionSales,
        BigDecimal resales,
        BigDecimal services,
        BigDecimal salesReturns,
        BigDecimal net
) {
}
