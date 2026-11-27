package br.com.cshub.taxify.enumerated;

/**
 * Who may have the presumed CBS credit on the stock of 31/12/2026, by the registered regime (spec 2026-11-27, F5a,
 * CE-RN-03, decision P-01: LC 214/2025, art. 381; Decreto 12.955/2026). Declared in the default list order (CE-RN-11).
 * Not a credit calculation.
 */
public enum StockSurveyClassification {
    /** Lucro Presumido in Dec/2026: hypothesis I (art. 381, I). */
    HYPOTHESIS_I,
    /** Lucro Real in Dec/2026: hypotheses II and III (art. 381, II and III). */
    HYPOTHESIS_II_III,
    /** No regime registered (or OTHER). */
    NO_REGIME,
    /** Simples Nacional/MEI in Dec/2026 or from Jan/2027: out of the credit by the adopted framing. */
    OUT_OF_SCOPE
}
