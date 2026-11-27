package br.com.cshub.taxify.domain.reform.stock;

import br.com.cshub.taxify.enumerated.StockSurveyRegimeSource;
import br.com.cshub.taxify.enumerated.TaxRegime;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CE-RN-02, the regime a company is classified by (value object, pure):
 * <ul>
 *   <li>{@code regime} - the period in force on 01/12/2026 (the greatest {@code valid_from} &le; 2026-12-01, a null
 *       {@code valid_from} being "since always"); with no such period, the company registry ({@code taxRegimeType});</li>
 *   <li>{@code since} - the {@code valid_from} of that period (null = since always, or the registry);</li>
 *   <li>{@code nextYearRegime} - the period starting exactly on 2027-01-01, when there is one (a 2026 period that
 *       "goes on" is not a 2027 registration).</li>
 * </ul>
 */
public record ReferenceRegime(TaxRegime regime, LocalDate since, StockSurveyRegimeSource source, TaxRegime nextYearRegime) {

    /** One period of the company's regime history. */
    public record Period(LocalDate validFrom, TaxRegime regime) {
    }

    public static ReferenceRegime resolve(List<Period> history, TaxRegime registry) {
        TaxRegime nextYear = history.stream()
                .filter(period -> StockSurveyCalendar.NEXT_YEAR_START.equals(period.validFrom()))
                .map(Period::regime).findFirst().orElse(null);
        Optional<Period> inForce = history.stream()
                .filter(period -> period.validFrom() == null || !period.validFrom().isAfter(StockSurveyCalendar.REFERENCE_DATE))
                .max(Comparator.comparing(Period::validFrom, Comparator.nullsFirst(Comparator.naturalOrder())));
        return inForce
                .map(period -> new ReferenceRegime(period.regime(), period.validFrom(), StockSurveyRegimeSource.HISTORY, nextYear))
                .orElseGet(() -> new ReferenceRegime(registry, null, StockSurveyRegimeSource.REGISTRATION, nextYear));
    }

    public boolean hasNextYearRegime() {
        return Objects.nonNull(nextYearRegime);
    }
}
