package br.com.cshub.taxify.dto.fiscalxml;

import java.math.BigDecimal;

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
        BigDecimal valorCofins
) {
}
