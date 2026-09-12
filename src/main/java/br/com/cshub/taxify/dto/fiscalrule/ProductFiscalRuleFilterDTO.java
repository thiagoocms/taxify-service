package br.com.cshub.taxify.dto.fiscalrule;

public record ProductFiscalRuleFilterDTO(
        String id,
        String companyId,
        String codigoProduto,
        Boolean deleted
) {
}
