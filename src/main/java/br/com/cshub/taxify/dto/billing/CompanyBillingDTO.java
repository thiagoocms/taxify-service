package br.com.cshub.taxify.dto.billing;

import br.com.cshub.taxify.enumerated.BillingProviderType;

import java.math.BigDecimal;
import java.util.List;

/**
 * Billing picture of one company: which external customer it's linked to, what's open and
 * overdue, and the charges themselves (most recent first).
 */
public record CompanyBillingDTO(
        BillingProviderType provider,
        String externalCustomerId,
        String externalCustomerName,
        BigDecimal openAmount,
        BigDecimal overdueAmount,
        long overdueCount,
        List<BillingChargeDTO> charges
) {
}
