package br.com.cshub.taxify.dto.fiscalrule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A suggested rule for one product, from how it was classified on the company's notes.
 * {@code campos} is keyed by the {@link ProductFiscalRuleDTO} field names (cfopCorreto,
 * ncmCorreto, cstIcms, aliquotaPis...) so the rule form can be prefilled with it; a field
 * that never appears on the notes is absent.
 *
 * @param ocorrencias items of this product on the notes
 * @param possuiRegra    the product already has a rule
 * @param cfopFornecedor the supplier's exit CFOP most used on the notes - {@code campos.cfopCorreto}
 *                       is the entry CFOP equivalent to it ({@code EntryCfops}), with the first
 *                       digit of an internal purchase (the rule adapts it to each note's operation)
 * @param documentoFornecedor CNPJ/CPF of the supplier whose code this is (the product code on a
 *                            received note is the supplier's)
 * @param nomeFornecedor      the supplier's name on the notes
 * @param unitPrice           the commercial unit price it was bought at (spec PU) - informative, never part of
 *                            {@code campos}; null when no item has a price and a quantity
 */
public record ProductRuleSuggestionDTO(
        String codigoProduto,
        String descricaoProduto,
        long ocorrencias,
        boolean possuiRegra,
        Map<String, SuggestedValue> campos,
        SuggestedValue cfopFornecedor,
        String documentoFornecedor,
        String nomeFornecedor,
        UnitPrice unitPrice
) {

    /**
     * The commercial unit price ({@code vUnCom}) of the predominant unit (PU-RN-04 to 06), 4 places.
     *
     * @param average         weighted by the quantity: Σ(qCom × vUnCom) ÷ Σ qCom, HALF_UP
     * @param unit            the predominant unit, trimmed and in upper case ("—" without one)
     * @param itemsConsidered items of that unit in the price
     * @param totalQuantity   Σ qCom of those items
     * @param otherUnits      the other units the product came in (left out of the price), most items first
     */
    public record UnitPrice(BigDecimal average, BigDecimal min, BigDecimal max, String unit, long itemsConsidered,
                            BigDecimal totalQuantity, List<OtherUnit> otherUnits) {
    }

    /** A unit the product also came in, with its number of items. */
    public record OtherUnit(String unit, long itemsConsidered) {
    }

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
