package br.com.cshub.taxify.dto.billing;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BillingChargeDTO(
        String id,
        @NotBlank
        String companyId,
        /** Optional: the contract the fee belongs to. */
        String subscriptionId,
        /** Read-only: MANUAL for charges typed in Taxify, the provider for synced ones (which can't be edited here). */
        BillingProviderType source,
        @Size(max = 500)
        String description,
        LocalDate competenceDate,
        @NotNull
        LocalDate dueDate,
        @NotNull @Positive
        BigDecimal amount,
        @PositiveOrZero
        BigDecimal paidAmount,
        /** Read-only: amount − paid. */
        BigDecimal openAmount,
        /** Only CANCELED is taken from the request; otherwise it follows amounts and due date. */
        ChargeStatus status
) {
}
