package br.com.cshub.taxify.enumerated;

/**
 * Por que o escritório está em somente leitura. {@code ACCESS_EXPIRED} é o {@code access_until} de uma liberação
 * manual vencido (F1-RN-07); NON_PAYMENT é da fase 2.
 */
public enum BlockedReason {
    TRIAL_EXPIRED,
    ACCESS_EXPIRED,
    NON_PAYMENT,
    MANUAL
}
