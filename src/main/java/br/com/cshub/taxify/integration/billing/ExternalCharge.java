package br.com.cshub.taxify.integration.billing;

import br.com.cshub.taxify.enumerated.ChargeStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExternalCharge(
        String id,
        String customerId,
        String description,
        LocalDate competenceDate,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal paidAmount,
        BigDecimal openAmount,
        ChargeStatus status
) {
}
