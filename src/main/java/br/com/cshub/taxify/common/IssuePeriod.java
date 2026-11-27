package br.com.cshub.taxify.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

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

    /**
     * The start of SIEG's day {@code day} - SIEG's windows are UTC days (00:00Z to 23:59:59.999Z) - as the stored issue
     * time, which is the note's own local time: for a note of Recife (-03:00), 21:00 of the day before. Counting what is
     * stored between {@link #siegStart} and {@link #siegEnd} compares with SIEG's count of the same window; a note of
     * another offset (-04:00, -05:00) can be off by its difference.
     */
    public static LocalDateTime siegStart(LocalDate day) {
        return day != null ? day.atStartOfDay(ZoneOffset.UTC).withZoneSameInstant(SIEG_LOCAL).toLocalDateTime() : LocalDateTime.MIN;
    }

    /** The end of SIEG's day {@code day} as the stored issue time: for Recife, 20:59:59.999999999 of the same day. */
    public static LocalDateTime siegEnd(LocalDate day) {
        return day != null ? day.atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).withZoneSameInstant(SIEG_LOCAL).toLocalDateTime()
                : LocalDateTime.MAX;
    }

    /** The local time the stored issue times are compared in (America/Recife). */
    private static final ZoneId SIEG_LOCAL = ZoneId.of("America/Recife");
}
