package br.com.cshub.taxify.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * The issue-date range of the period filters ({@code dataEmissaoInicio}/{@code dataEmissaoFim}):
 * whole days, an open end when a date is missing.
 */
public final class IssuePeriod {

    private IssuePeriod() {
    }

    /** Start of {@code day}; no day = no lower bound. */
    public static LocalDateTime start(LocalDate day) {
        return day != null ? day.atStartOfDay() : LocalDateTime.MIN;
    }

    /** Last instant of {@code day}; no day = no upper bound. */
    public static LocalDateTime end(LocalDate day) {
        return day != null ? day.atTime(LocalTime.MAX) : LocalDateTime.MAX;
    }
}
