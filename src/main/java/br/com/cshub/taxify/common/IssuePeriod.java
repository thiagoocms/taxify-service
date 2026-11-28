package br.com.cshub.taxify.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The issue-date range of the period filters ({@code dataEmissaoInicio}/{@code dataEmissaoFim}): whole days, an open
 * end when a date is missing - always <b>half-open</b>, {@code issueDate >= start and issueDate < endExclusive}.
 *
 * <p>Never an inclusive "last instant" ({@code LocalTime.MAX}, {@code 23:59:59.999999999}, {@code minusNanos(1)}):
 * the MySQL driver rounds the nanoseconds to the column's microseconds, so 23:59:59.999999999 is sent as 00:00:00 of the
 * next day and a note issued at exactly 00:00:00 of the next month fell into the month (QA of 2026-11-28).
 */
public final class IssuePeriod {

    private IssuePeriod() {
    }

    /** Start of {@code day}; no day = no lower bound. */
    public static LocalDateTime start(LocalDate day) {
        return day != null ? day.atStartOfDay() : LocalDateTime.MIN;
    }

    /** Start of the day after {@code day} - the exclusive end of a period that includes {@code day}; no day = no upper bound. */
    public static LocalDateTime endExclusive(LocalDate day) {
        return day != null ? day.plusDays(1).atStartOfDay() : LocalDateTime.MAX;
    }

    /** Start of the month. */
    public static LocalDateTime monthStart(YearMonth month) {
        return month.atDay(1).atStartOfDay();
    }

    /** Start of the next month - the exclusive end of {@code month}. */
    public static LocalDateTime monthEndExclusive(YearMonth month) {
        return month.plusMonths(1).atDay(1).atStartOfDay();
    }

    /**
     * The start of SIEG's day {@code day} - SIEG's windows are UTC days (00:00Z to 23:59:59.999Z) - as the stored issue
     * time, which is the note's own local time: for a note of Recife (-03:00), 21:00 of the day before. Counting what is
     * stored in [{@link #siegStart}, {@link #siegEndExclusive}) compares with SIEG's count of the same window; a note of
     * another offset (-04:00, -05:00) can be off by its difference.
     */
    public static LocalDateTime siegStart(LocalDate day) {
        return day != null ? day.atStartOfDay(ZoneOffset.UTC).withZoneSameInstant(SIEG_LOCAL).toLocalDateTime() : LocalDateTime.MIN;
    }

    /** The exclusive end of SIEG's day {@code day} as the stored issue time: for Recife, 21:00 of the same day. */
    public static LocalDateTime siegEndExclusive(LocalDate day) {
        return day != null ? siegStart(day.plusDays(1)) : LocalDateTime.MAX;
    }

    /** The local time the stored issue times are compared in (America/Recife). */
    private static final ZoneId SIEG_LOCAL = ZoneId.of("America/Recife");
}
