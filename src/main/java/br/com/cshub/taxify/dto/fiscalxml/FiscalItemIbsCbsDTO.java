package br.com.cshub.taxify.dto.fiscalxml;


import java.math.BigDecimal;

/**
 * The IBS/CBS group of a note item (Reforma Tributária - {@code det/imposto/IBSCBS}, NF-e
 * layout from NT 2025.002): CST, cClassTrib, the shared base and, per tax (IBS of the
 * state, IBS of the city, CBS), the rate, its reduction ({@code gRed/pRedAliq}) and the
 * value. Null when the note has no IBSCBS group (notes before 2026, or issuers not yet
 * filling it). Rates and reductions are percentages, as in the XML. {@code diferimentoOuDevolucao}
 * is true when any of the taxes has a gDif or gDevTrib group - then the value is no longer
 * base x rate and the calculation check stays out.
 */
public record FiscalItemIbsCbsDTO(
        String cst,
        String classificacaoTributaria,
        BigDecimal baseCalculo,
        BigDecimal aliquotaIbsUf,
        BigDecimal reducaoAliquotaIbsUf,
        BigDecimal valorIbsUf,
        BigDecimal aliquotaIbsMun,
        BigDecimal reducaoAliquotaIbsMun,
        BigDecimal valorIbsMun,
        BigDecimal valorIbs,
        BigDecimal aliquotaCbs,
        BigDecimal reducaoAliquotaCbs,
        BigDecimal valorCbs,
        Boolean diferimentoOuDevolucao
) {
}
