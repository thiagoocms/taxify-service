package br.com.cshub.taxify.enumerated;

/** Situação da assinatura do escritório ao Taxify (F1-RN-07). PAST_DUE é da fase 2. */
public enum FirmSubscriptionStatus {
    PENDING_VERIFICATION,
    TRIAL,
    ACTIVE,
    PAST_DUE,
    BLOCKED,
    SUSPENDED,
    CANCELED
}
