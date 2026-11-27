package br.com.cshub.taxify.dto.reform;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The detail of one company's stock credit survey (section 7): the fields of the row (flattened), the fixed notice
 * (CE-RN-13), where the regime came from, the breakdowns (CE-RN-05), the memory (CE-RN-14), when the refresh
 * cooldown frees ({@code refreshAvailableAt}, null = free) and whether the survey is closed (CE-RN-12).
 */
public record StockSurveyDetailDTO(
        @JsonUnwrapped StockSurveyRowDTO row,
        String notice,
        LocalDate referenceRegimeSince,
        String referenceRegimeSource,
        List<Month> purchasesByMonth,
        List<Cst> purchasesByPisCst,
        List<Ncm> topNcm,
        List<String> memory,
        OffsetDateTime refreshAvailableAt,
        boolean closed
) {

    /** {@code month} "2026-01". */
    public record Month(String month, BigDecimal amount, int invoices) {
    }

    /** {@code cst} trimmed, or {@code NO_CST}. */
    public record Cst(String cst, BigDecimal amount, int items) {
    }

    /** {@code ncm} trimmed, or {@code NO_NCM}. */
    public record Ncm(String ncm, BigDecimal amount, int items) {
    }
}
