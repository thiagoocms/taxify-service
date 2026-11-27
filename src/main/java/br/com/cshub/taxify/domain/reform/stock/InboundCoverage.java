package br.com.cshub.taxify.domain.reform.stock;

import br.com.cshub.taxify.enumerated.StockSurveyCoverage;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CE-RN-06, the capture coverage of the 2026 inbound notes (any CFOP) - an indicator, not a tax rule:
 * <ul>
 *   <li>{@code monthsEvaluated} - while the date of Recife is in 2026, the current month - 1 (the current one is still
 *       open); from 2027-01-01 on, 12;</li>
 *   <li>{@code coverage} - NO_INBOUND without notes; COMPLETE when every month 1..monthsEvaluated has a note;
 *       PARTIAL otherwise.</li>
 * </ul>
 */
public record InboundCoverage(int monthsWithInbound, int monthsEvaluated, StockSurveyCoverage coverage, LocalDate first,
                              LocalDate last) {

    public static InboundCoverage of(Collection<StockSurveyFigures.InboundMonth> inbound, LocalDate today) {
        Set<Integer> months = inbound.stream().map(StockSurveyFigures.InboundMonth::month).collect(Collectors.toSet());
        int evaluated = monthsEvaluated(today);
        LocalDate first = inbound.stream().map(StockSurveyFigures.InboundMonth::first).filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        LocalDate last = inbound.stream().map(StockSurveyFigures.InboundMonth::last).filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
        return new InboundCoverage(months.size(), evaluated, coverage(months, evaluated), first, last);
    }

    static int monthsEvaluated(LocalDate today) {
        if (today.getYear() > StockSurveyCalendar.YEAR) {
            return 12;
        }
        if (today.getYear() < StockSurveyCalendar.YEAR) {
            return 0;
        }
        return today.getMonthValue() - 1;
    }

    private static StockSurveyCoverage coverage(Set<Integer> months, int evaluated) {
        if (months.isEmpty()) {
            return StockSurveyCoverage.NO_INBOUND;
        }
        for (int month = 1; month <= evaluated; month++) {
            if (!months.contains(month)) {
                return StockSurveyCoverage.PARTIAL;
            }
        }
        return StockSurveyCoverage.COMPLETE;
    }
}
