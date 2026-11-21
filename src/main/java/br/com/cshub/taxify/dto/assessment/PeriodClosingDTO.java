package br.com.cshub.taxify.dto.assessment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Fechamento of the month: status, who closed it, the guide, and the checklist counts
 * (entries still to review). {@code status} OPEN when the month was never closed.
 */
public record PeriodClosingDTO(
        String status,
        LocalDateTime closedAt,
        String closedBy,
        boolean guideIssued,
        BigDecimal guideAmount,
        LocalDate guideDueDate,
        String notes,
        long pendingReviews
) {

    public static PeriodClosingDTO open(long pendingReviews) {
        return new PeriodClosingDTO("OPEN", null, null, false, null, null, null, pendingReviews);
    }
}
