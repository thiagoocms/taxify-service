package br.com.cshub.taxify.dto.fiscalrule;

/**
 * A product as it appears in the company's stored notes - what a {@link
 * br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule} is registered against.
 *
 * @param ncm         NCM seen on the notes (a suggestion for the rule's "NCM correto")
 * @param cfop        CFOP seen on the notes - for reference, it may be exactly what's wrong
 * @param ocorrencias how many note items carry this code
 * @param possuiRegra the company already has a rule for this code
 * @param label       "codigo - descricao", for display in pickers
 */
public record CompanyProductDTO(
        String codigo,
        String descricao,
        String ncm,
        String cfop,
        long ocorrencias,
        boolean possuiRegra,
        String label
) {
}
