package br.com.cshub.taxify.dto.fiscalrule;

import jakarta.validation.constraints.NotBlank;

public record ProductFiscalRuleDTO(
        String id,
        @NotBlank
        String companyId,
        @NotBlank
        String codigoProduto,
        String descricaoProduto,
        String ncmCorreto,
        @NotBlank
        String cfopCorreto,
        String observacoes,
        Boolean deleted
) {
}
