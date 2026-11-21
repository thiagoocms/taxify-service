package br.com.cshub.taxify.enumerated;

/** Status of one fee charge (installment), provider-neutral - each BillingProvider maps its own. */
public enum ChargeStatus {
    OPEN,
    PAID,
    PARTIALLY_PAID,
    OVERDUE,
    RENEGOTIATED,
    CANCELED
}
