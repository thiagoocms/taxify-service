package br.com.cshub.taxify.enumerated;

/** What a Fronteira PE rule sets: the standard calculation's tables, or the accountant's own formula. */
public enum FronteiraRuleKind {
    /** Internal ICMS rate of PE - without NCM it is the modal one, with NCM (prefix) an exception. */
    ALIQUOTA_INTERNA,
    /** Product under ST with encerramento (Anexo 37): NCM (prefix) and/or CEST with its original MVA. */
    ST_MVA,
    /**
     * Carga líquida over the starting value, no credit: the company's credenciamento (description = the
     * credenciamento as the extract names it, any regime), or the art. 363-A of a Simples company (no description).
     */
    SIMPLES_PERCENTUAL,
    /** The accountant's own formula - takes the place of the standard calculation for what it matches. */
    FORMULA
}
