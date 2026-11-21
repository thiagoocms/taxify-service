package br.com.cshub.taxify.enumerated;

/**
 * Situation of a note at SEFAZ, as far as its events tell: AUTHORIZED until a registered
 * cancellation event (110111, or 110112 by substitution) arrives. A canceled note is still
 * listed - the fiscal team needs to see it - but never counted in totals or rectified.
 */
public enum FiscalDocumentStatus {
    AUTHORIZED,
    CANCELED
}
