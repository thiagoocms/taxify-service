package br.com.cshub.taxify.domain.reform.stock;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The dates of the stock credit survey (spec 2026-11-27, F5a), constants of the rule - not environment variables:
 * inventory on 31/12/2026, credit taken until 30/06/2027 (decision P-02). Every time here is Recife's.
 */
public final class StockSurveyCalendar {

    /** The only survey year of this phase (CE-RN-01). */
    public static final int YEAR = 2026;
    /** The regime of this day is the regime of Dec/2026 and of 31/12/2026 (CE-RN-02). */
    public static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 12, 1);
    /** A period starting on this day is the regime registered for 2027 (CE-RN-02). */
    public static final LocalDate NEXT_YEAR_START = LocalDate.of(2027, 1, 1);
    /** Last day the survey is open (CE-RN-12, P-02). */
    public static final LocalDate CLOSES_ON = LocalDate.of(2027, 6, 30);

    private StockSurveyCalendar() {
    }

    public static boolean isValidYear(Integer year) {
        return year != null && year == YEAR;
    }

    /** CE-RN-12: from 01/07/2027 00:00 of Recife on, the routine stops and the writes answer 409. */
    public static boolean isClosed(LocalDateTime recifeNow) {
        return !recifeNow.toLocalDate().isBefore(CLOSES_ON.plusDays(1));
    }

    /** First instant of the survey year (inclusive) - the stored issue_date is the local time of dhEmi (CE-RN-04). */
    public static LocalDateTime yearStart() {
        return LocalDate.of(YEAR, 1, 1).atStartOfDay();
    }

    /** First instant after the survey year (exclusive). */
    public static LocalDateTime yearEnd() {
        return LocalDate.of(YEAR + 1, 1, 1).atStartOfDay();
    }
}
