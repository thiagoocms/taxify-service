package br.com.cshub.taxify.dto.plan;

import br.com.cshub.taxify.enumerated.BillingCycle;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

public record PlanDTO(
        String id,
        @NotBlank
        String name,
        String description,
        Set<PlanServiceEnum> services,
        /** Reference (list) price; each subscription's negotiated fee may differ. */
        @PositiveOrZero
        BigDecimal listPrice,
        BillingCycle billingCycle,
        Boolean deleted
) {
}
