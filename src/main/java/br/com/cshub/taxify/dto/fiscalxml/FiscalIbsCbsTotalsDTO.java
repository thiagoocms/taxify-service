package br.com.cshub.taxify.dto.fiscalxml;


import java.math.BigDecimal;

/**
 * The note's IBS/CBS totals ({@code total/IBSCBSTot}) - what the sum of the items'
 * {@link FiscalItemIbsCbsDTO} has to match. Null when the note has no IBSCBSTot group.
 */
public record FiscalIbsCbsTotalsDTO(
        BigDecimal baseCalculo,
        BigDecimal valorIbsUf,
        BigDecimal valorIbsMun,
        BigDecimal valorIbs,
        BigDecimal valorCbs
) {
}
