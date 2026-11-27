package br.com.cshub.taxify.dto.reform;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One company of the stock credit survey list (section 7). {@code cnpj} with 14 digits, no mask; money with 2
 * decimals; date-times in Recife with the offset.
 */
public record StockSurveyRowDTO(
        UUID companyId,
        String companyName,
        String cnpj,
        String referenceRegime,
        String nextYearRegime,
        String classification,
        String classificationReason,
        BigDecimal yearPurchases,
        int purchaseItems,
        int inboundInvoices,
        int monthsWithInbound,
        int monthsEvaluated,
        String coverage,
        LocalDate firstInbound,
        LocalDate lastInbound,
        String status,
        String statusChangedBy,
        OffsetDateTime statusChangedAt,
        String note,
        int version,
        OffsetDateTime calculatedAt
) {
}
