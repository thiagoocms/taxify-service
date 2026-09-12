package br.com.cshub.taxify.dto.fiscalxml;

import java.math.BigDecimal;

public record FiscalXmlTotaisDTO(
        BigDecimal baseCalculoIcms,
        BigDecimal valorIcms,
        BigDecimal valorIcmsDesonerado,
        BigDecimal valorFcp,
        BigDecimal baseCalculoIcmsSt,
        BigDecimal valorIcmsSt,
        BigDecimal valorFcpSt,
        BigDecimal valorProdutos,
        BigDecimal valorFrete,
        BigDecimal valorSeguro,
        BigDecimal valorDesconto,
        BigDecimal valorImportacao,
        BigDecimal valorIpi,
        BigDecimal valorPis,
        BigDecimal valorCofins,
        BigDecimal valorOutrasDespesas,
        BigDecimal valorTotalNota,
        BigDecimal valorAproximadoTributos
) {
}
