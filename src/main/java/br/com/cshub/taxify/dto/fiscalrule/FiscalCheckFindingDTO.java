package br.com.cshub.taxify.dto.fiscalrule;

import br.com.cshub.taxify.enumerated.DivergenceSeverity;

import java.time.LocalDateTime;

/**
 * One finding of the fiscal conferência: a note (and, for item findings, one of its items)
 * and what is wrong with it. {@code numeroItem}/{@code codigoProduto} are null for findings
 * about the whole note (totals, a group missing from every item). {@code codigo} is stable
 * (e.g. IBSCBS_RATE) - filter on it; {@code mensagem} is for people.
 */
public record FiscalCheckFindingDTO(
        String documentId,
        String accessKey,
        LocalDateTime issueDate,
        String emitenteNome,
        Integer numeroItem,
        String codigoProduto,
        String descricaoProduto,
        String codigo,
        String campo,
        String valorEncontrado,
        String valorEsperado,
        DivergenceSeverity severidade,
        String mensagem
) {
}
