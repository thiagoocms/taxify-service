package br.com.cshub.taxify.integration.billing;

import br.com.cshub.taxify.enumerated.BillingCycle;
import br.com.cshub.taxify.enumerated.SubscriptionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A recurring contract in the provider. {@code amount} and {@code cycle} are optional: some
 * providers (Conta Azul) don't return them, in which case the fee comes from the charges.
 */
public record ExternalContract(
        String id,
        String number,
        String customerId,
        SubscriptionStatus status,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate nextDueDate,
        BigDecimal amount,
        BillingCycle cycle
) {
}
