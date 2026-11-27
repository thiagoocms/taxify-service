package br.com.cshub.taxify.domain.reform;

import br.com.cshub.taxify.enumerated.IbsCbsRateStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The rules of the IBS/CBS reference rates per year (spec 2026-11-27, F1a-0), pure and without I/O:
 * <ul>
 *   <li>AL-RN-03 - what a year may hold ({@link #check});</li>
 *   <li>AL-RN-05 - which of the current and the next year have no LAW rate ({@link #yearsWithoutLaw}).</li>
 * </ul>
 */
public final class IbsCbsRatePolicy {

    public static final int MIN_YEAR = 2026;
    public static final int MAX_YEAR = 2040;
    public static final int RATE_SCALE = 4;
    public static final BigDecimal MAX_RATE = new BigDecimal("100");
    public static final int LEGAL_BASIS_MAX = 500;
    public static final int SOURCE_URL_MAX = 300;
    public static final String SOURCE_URL_PREFIX = "https://";

    /** Why a year can't be saved; {@code field} is the JSON field of a rate for {@link Kind#RATE_INVALID}. */
    public record Violation(Kind kind, String field) {
    }

    public enum Kind {
        YEAR_INVALID, STATUS_INVALID, RATE_INVALID, BASIS_REQUIRED, BASIS_INVALID
    }

    /** The three rates in the order they are checked, with their JSON field names. */
    public record RateInput(String field, BigDecimal value) {
    }

    private IbsCbsRatePolicy() {
    }

    /**
     * AL-RN-03, in this order: the year (2026 to 2040), the status, each rate given (0 to 100, at most 4 decimals,
     * mandatory with LAW and ESTIMATE), then the legal basis and the link (mandatory with LAW; same limits otherwise).
     *
     * @param values the normalized values ({@link #normalize}); status null = missing or unknown
     */
    public static Optional<Violation> check(int year, IbsCbsRateValues values) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            return Optional.of(new Violation(Kind.YEAR_INVALID, null));
        }
        IbsCbsRateStatus status = values.status();
        if (status == null) {
            return Optional.of(new Violation(Kind.STATUS_INVALID, null));
        }
        for (RateInput rate : rates(values)) {
            if (rate.value() == null ? status.requiresAllRates() : !isValidRate(rate.value())) {
                return Optional.of(new Violation(Kind.RATE_INVALID, rate.field()));
            }
        }
        boolean basisOk = values.legalBasis() == null || values.legalBasis().length() <= LEGAL_BASIS_MAX;
        boolean urlOk = values.sourceUrl() == null || isValidUrl(values.sourceUrl());
        if (status.requiresLegalBasis() && (values.legalBasis() == null || values.sourceUrl() == null || !basisOk || !urlOk)) {
            return Optional.of(new Violation(Kind.BASIS_REQUIRED, null));
        }
        if (!basisOk || !urlOk) {
            return Optional.of(new Violation(Kind.BASIS_INVALID, null));
        }
        return Optional.empty();
    }

    /** 0 &le; x &le; 100 with at most 4 decimals - a fifth decimal is refused, never rounded. */
    public static boolean isValidRate(BigDecimal rate) {
        return rate.signum() >= 0 && rate.compareTo(MAX_RATE) <= 0 && rate.stripTrailingZeros().scale() <= RATE_SCALE;
    }

    /**
     * Texts trimmed (blank = null) and valid rates at scale 4 - exact, since a valid rate has at most 4 decimals
     * ({@link RoundingMode#UNNECESSARY}). Invalid rates are kept as given, for {@link #check} to refuse.
     */
    public static IbsCbsRateValues normalize(IbsCbsRateStatus status, BigDecimal cbs, BigDecimal ibsState,
                                             BigDecimal ibsMunicipal, String legalBasis, String sourceUrl) {
        return new IbsCbsRateValues(status, scaled(cbs), scaled(ibsState), scaled(ibsMunicipal), trimmed(legalBasis),
                trimmed(sourceUrl));
    }

    /** AL-RN-05: the current year (Recife) and the next one, when they have no row that generates findings. */
    public static List<Integer> yearsWithoutLaw(LocalDate today, Collection<Integer> lawYears) {
        Set<Integer> law = Set.copyOf(lawYears);
        List<Integer> missing = new ArrayList<>();
        for (int year = today.getYear(); year <= today.getYear() + 1; year++) {
            if (!law.contains(year)) {
                missing.add(year);
            }
        }
        return missing;
    }

    private static List<RateInput> rates(IbsCbsRateValues values) {
        return List.of(new RateInput("cbsRate", values.cbsRate()), new RateInput("ibsStateRate", values.ibsStateRate()),
                new RateInput("ibsMunicipalRate", values.ibsMunicipalRate()));
    }

    private static boolean isValidUrl(String url) {
        return url.length() <= SOURCE_URL_MAX && url.startsWith(SOURCE_URL_PREFIX) && url.length() > SOURCE_URL_PREFIX.length();
    }

    private static BigDecimal scaled(BigDecimal rate) {
        return rate == null || !isValidRate(rate) ? rate : rate.setScale(RATE_SCALE, RoundingMode.UNNECESSARY);
    }

    private static String trimmed(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
