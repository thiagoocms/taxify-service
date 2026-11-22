package br.com.cshub.taxify.dto.assessment;

import java.math.BigDecimal;

/**
 * IBS/CBS of the period, from the IBSCBS group of the notes: what the company's sales
 * show (débito) and what its purchases show (crédito). Informative - in 2026 (test year)
 * the amounts are reported but not paid; from 2027 the CBS is due. Canceled notes and
 * returns are not netted beyond what the notes themselves carry.
 *
 * @param notasSaida   sales notes with an IBS/CBS group in the period
 * @param notasEntrada purchase notes with an IBS/CBS group in the period
 */
public record IbsCbsSummaryDTO(
        BigDecimal debitoIbs,
        BigDecimal debitoCbs,
        BigDecimal creditoIbs,
        BigDecimal creditoCbs,
        BigDecimal saldoIbs,
        BigDecimal saldoCbs,
        long notasSaida,
        long notasEntrada,
        String observacao
) {
}
