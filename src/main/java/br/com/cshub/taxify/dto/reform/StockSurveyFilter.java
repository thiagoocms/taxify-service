package br.com.cshub.taxify.dto.reform;

import br.com.cshub.taxify.enumerated.StockSurveyClassification;
import br.com.cshub.taxify.enumerated.StockSurveyContactStatus;
import br.com.cshub.taxify.enumerated.StockSurveyCoverage;

import java.util.Set;

/**
 * The checked filters of the stock credit survey list and CSV (section 7). {@code search} trimmed (company name);
 * {@code searchDigits} its digits when there are at least 3 (CNPJ with or without mask), otherwise null.
 */
public record StockSurveyFilter(int year, Set<StockSurveyClassification> classifications,
                                Set<StockSurveyContactStatus> statuses, Set<StockSurveyCoverage> coverages, String search,
                                String searchDigits, Sort sort, int page, int size) {

    /** The sortable fields; {@code field} null = the default order of CE-RN-11. */
    public enum SortField {
        CLASSIFICATION, YEAR_PURCHASES, COMPANY_NAME, STATUS_CHANGED_AT
    }

    public record Sort(SortField field, boolean ascending) {
    }
}
