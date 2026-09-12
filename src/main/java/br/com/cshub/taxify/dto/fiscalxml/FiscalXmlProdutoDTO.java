package br.com.cshub.taxify.dto.fiscalxml;

import java.math.BigDecimal;

public record FiscalXmlProdutoDTO(
        Integer numeroItem,
        String codigo,
        String codigoBarras,
        String descricao,
        String ncm,
        String cfop,
        String unidadeComercial,
        BigDecimal quantidadeComercial,
        BigDecimal valorUnitarioComercial,
        BigDecimal valorTotal,
        String unidadeTributavel,
        BigDecimal quantidadeTributavel,
        BigDecimal valorUnitarioTributavel,
        FiscalXmlImpostoItemDTO impostos
) {
}
