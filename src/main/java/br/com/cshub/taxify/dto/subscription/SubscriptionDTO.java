package br.com.cshub.taxify.dto.subscription;

import br.com.cshub.taxify.enumerated.BillingCycle;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.SubscriptionStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Plan + period chosen in Taxify, plus the contract/fee. When {@code source} is a provider
 * (not MANUAL), fee, status, contract and next due date are synced from it and ignored on
 * update; {@code externalContract*}, {@code nextDueDate}, {@code lastSyncedAt} and
 * {@code syncWarning} are always read-only.
 */
public record SubscriptionDTO(
        String id,
        @NotBlank
        String companyId,
        @NotBlank
        String planId,
        @NotNull
        LocalDate startDate,
        @NotNull
        LocalDate endDate,
        @PositiveOrZero
        BigDecimal fee,
        BillingCycle billingCycle,
        @Min(1) @Max(31)
        Integer billingDay,
        SubscriptionStatus status,
        BillingProviderType source,
        String externalContractId,
        String externalContractNumber,
        LocalDate nextDueDate,
        LocalDateTime lastSyncedAt,
        String syncWarning,
        Boolean deleted
) {
}
