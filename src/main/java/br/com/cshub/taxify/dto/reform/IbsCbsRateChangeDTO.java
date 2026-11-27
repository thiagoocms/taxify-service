package br.com.cshub.taxify.dto.reform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One event of a year's history (AL-RN-04): {@code action} CREATED or UPDATED; {@code changedAt} in Recife. */
public record IbsCbsRateChangeDTO(
        String action,
        String statusBefore,
        String statusAfter,
        BigDecimal cbsBefore,
        BigDecimal cbsAfter,
        BigDecimal ibsStateBefore,
        BigDecimal ibsStateAfter,
        BigDecimal ibsMunicipalBefore,
        BigDecimal ibsMunicipalAfter,
        String legalBasisAfter,
        String sourceUrlAfter,
        String changedBy,
        OffsetDateTime changedAt
) {
}
