package br.com.cshub.taxify.dto.company;

import br.com.cshub.taxify.enumerated.BusinessActivity;
import br.com.cshub.taxify.enumerated.SimplesAnnex;
import br.com.cshub.taxify.enumerated.TaxRegime;
import jakarta.validation.constraints.NotNull;

/**
 * One regime period. {@code validFrom} is a month ("2026-01"); null = since always.
 * {@code validUntil} (read-only) is the month before the next period, null = current.
 */
public record CompanyTaxRegimeDTO(
        String id,
        @NotNull TaxRegime taxRegime,
        BusinessActivity businessActivity,
        SimplesAnnex simplesServiceAnnex,
        String validFrom,
        String validUntil,
        String label
) {
}
