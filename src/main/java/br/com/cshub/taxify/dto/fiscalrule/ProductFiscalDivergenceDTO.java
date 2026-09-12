package br.com.cshub.taxify.dto.fiscalrule;

import java.time.LocalDateTime;

/**
 * One already-downloaded note's item whose CFOP (and/or NCM, when the rule pins one)
 * doesn't match the {@link br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule}
 * registered for that product - i.e. something to rectify.
 */
public record ProductFiscalDivergenceDTO(
        String documentId,
        String accessKey,
        LocalDateTime issueDate,
        String codigoProduto,
        String descricaoProduto,
        String cfopAtual,
        String cfopCorreto,
        String ncmAtual,
        String ncmCorreto
) {
}
