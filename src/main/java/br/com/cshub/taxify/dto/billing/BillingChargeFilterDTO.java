package br.com.cshub.taxify.dto.billing;

import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;

public record BillingChargeFilterDTO(
        String id,
        String companyId,
        ChargeStatus status,
        BillingProviderType source,
        Boolean deleted
) {
}
