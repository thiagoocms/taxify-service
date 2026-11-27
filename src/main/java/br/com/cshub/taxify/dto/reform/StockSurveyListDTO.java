package br.com.cshub.taxify.dto.reform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The stock credit survey list (section 7): the fixed notice (CE-RN-13), the summary of every row that passes the
 * filters (not only the page; {@code notCalculated} = companies in scope with no calculated survey, outside the
 * page) and the page.
 */
public record StockSurveyListDTO(String notice, Summary summary, Page page) {

    public record Summary(long companies, long notCalculated, Map<String, Long> byClassification,
                          Map<String, Long> byStatus, BigDecimal yearPurchasesTotal, OffsetDateTime oldestCalculatedAt) {
    }

    public record Page(List<StockSurveyRowDTO> content, long totalElements, int totalPages, int number, int size) {
    }
}
