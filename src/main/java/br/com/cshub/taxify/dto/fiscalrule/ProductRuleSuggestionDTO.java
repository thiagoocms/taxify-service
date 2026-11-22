package br.com.cshub.taxify.dto.fiscalrule;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A suggested rule for one product, from how it was classified on the company's notes.
 * {@code campos} is keyed by the {@link ProductFiscalRuleDTO} field names (cfopCorreto,
 * ncmCorreto, cstIcms, aliquotaPis...) so the rule form can be prefilled with it; a field
 * that never appears on the notes is absent.
 *
 * @param ocorrencias items of this product on the notes
 * @param possuiRegra the product already has a rule
 */
public record ProductRuleSuggestionDTO(
        String codigoProduto,
        String descricaoProduto,
        long ocorrencias,
        boolean possuiRegra,
        Map<String, SuggestedValue> campos
) {

    /**
     * The most frequent value of one field.
     *
     * @param ocorrencias items with this value
     * @param percentual  share of the items that have the field at all with this value - 100
     *                    means the notes always agree; lower means they differ and the
     *                    suggestion needs a look
     */
    public record SuggestedValue(String valor, long ocorrencias, BigDecimal percentual) {
    }
}
