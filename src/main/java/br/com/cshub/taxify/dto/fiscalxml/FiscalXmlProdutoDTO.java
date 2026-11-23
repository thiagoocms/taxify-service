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
        FiscalXmlImpostoItemDTO impostos,
        /** CFOP as issued, only when the rectified version changed it ({@code cfop} then holds the rectified one). */
        String cfopEmitido
) {
    public FiscalXmlProdutoDTO(Integer numeroItem, String codigo, String codigoBarras, String descricao, String ncm, String cfop,
                               String unidadeComercial, BigDecimal quantidadeComercial, BigDecimal valorUnitarioComercial,
                               BigDecimal valorTotal, String unidadeTributavel, BigDecimal quantidadeTributavel,
                               BigDecimal valorUnitarioTributavel, FiscalXmlImpostoItemDTO impostos) {
        this(numeroItem, codigo, codigoBarras, descricao, ncm, cfop, unidadeComercial, quantidadeComercial, valorUnitarioComercial,
                valorTotal, unidadeTributavel, quantidadeTributavel, valorUnitarioTributavel, impostos, null);
    }

    /** The same item showing {@code rectifiedCfop}, keeping the issued one in {@link #cfopEmitido}. */
    public FiscalXmlProdutoDTO withRectifiedCfop(String rectifiedCfop) {
        if (rectifiedCfop == null || rectifiedCfop.equals(cfop == null ? null : cfop.strip())) {
            return this;
        }
        return new FiscalXmlProdutoDTO(numeroItem, codigo, codigoBarras, descricao, ncm, rectifiedCfop, unidadeComercial,
                quantidadeComercial, valorUnitarioComercial, valorTotal, unidadeTributavel, quantidadeTributavel,
                valorUnitarioTributavel, impostos, cfop);
    }
}
