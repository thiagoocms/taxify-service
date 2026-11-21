package br.com.cshub.taxify.dto.billing;

import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record BillingChargeDTO(
        String id,
        String companyId,
        String subscriptionId,
        BillingProviderType source,
        String description,
        LocalDate competenceDate,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal paidAmount,
        BigDecimal openAmount,
        ChargeStatus status
) {
}
