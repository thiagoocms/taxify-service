package br.com.cshub.taxify.enumerated;

/**
 * How the daily NFS-e capture uses the provider's count (A1, TAXIFY_SIEG_NFSE_COUNT_GATE): OFF downloads the whole
 * lookback every day; SHADOW (default, decision of 2026-11-27) downloads the same way and only logs what the count would
 * have skipped; ON downloads a month only when the count shows something missing.
 */
public enum ServiceInvoiceCountGate {
    OFF,
    SHADOW,
    ON
}
