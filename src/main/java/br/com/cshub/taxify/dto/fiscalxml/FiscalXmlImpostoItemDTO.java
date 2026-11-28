package br.com.cshub.taxify.dto.fiscalxml;


import java.math.BigDecimal;

/**
 * Taxes of one note item. {@code situacaoTributariaIcms} is the CST or the CSOSN, whichever
 * the note used; {@code detalhe} has the rest of the tax detail (and the CSOSN on its own),
 * {@code ibsCbs} the Reforma Tributária group - null when the note has none.
 */
public record FiscalXmlImpostoItemDTO(
        String situacaoTributariaIcms,
        BigDecimal baseCalculoIcms,
        BigDecimal aliquotaIcms,
        BigDecimal valorIcms,
        String situacaoTributariaIpi,
        BigDecimal valorIpi,
        String situacaoTributariaPis,
        BigDecimal valorPis,
        String situacaoTributariaCofins,
        BigDecimal valorCofins,
        FiscalItemTaxDetailDTO detalhe,
        FiscalItemIbsCbsDTO ibsCbs
) {
}
