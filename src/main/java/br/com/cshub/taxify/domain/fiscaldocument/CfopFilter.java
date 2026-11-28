package br.com.cshub.taxify.domain.fiscaldocument;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The CFOPs a screen is filtered by (spec 2026-11-27 filtro de CFOP múltiplo): canonical - only digits, 4 of them, no
 * duplicate, in ascending order (CF-RN-01 to 05). A note (or a Fronteira line) matches when it has at least one of them
 * (CF-RN-06, "or"); an empty filter matches everything. Pure: no I/O, no message - {@link Invalid} says what was wrong
 * and the validation layer turns it into the error body.
 */
public final class CfopFilter {

    /** CF-RN-04 (P-CF-04): at most this many distinct CFOPs. */
    public static final int MAX_ITEMS = 20;

    private static final Pattern FOUR_DIGITS = Pattern.compile("\\d{4}");
    private static final Pattern NOT_A_DIGIT = Pattern.compile("\\D");
    /** {@code in ()} is invalid in MySQL and in Hibernate: an empty filter binds this, never compared (the flag goes first). */
    private static final List<String> SENTINEL = List.of("");
    private static final CfopFilter NONE = new CfopFilter(List.of());

    private final List<String> cfops;

    private CfopFilter(List<String> cfops) {
        this.cfops = List.copyOf(cfops);
    }

    /** No CFOP filter. */
    public static CfopFilter none() {
        return NONE;
    }

    /** CF-RN-01: "1.102" -> "1102", " 5102 " -> "5102"; null -> "". */
    public static String normalize(String raw) {
        return raw == null ? "" : NOT_A_DIGIT.matcher(raw).replaceAll("");
    }

    /**
     * CF-RN-01 to 05: normalized, checked (4 digits each, at most {@link #MAX_ITEMS} distinct), duplicates dropped and
     * sorted. Null or empty = no filter.
     *
     * @throws Invalid a value without 4 digits after normalized (an empty one included) or too many distinct values
     */
    public static CfopFilter of(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return NONE;
        }
        List<String> invalid = new ArrayList<>();
        TreeSet<String> canonical = new TreeSet<>();
        for (String value : raw) {
            String normalized = normalize(value);
            if (FOUR_DIGITS.matcher(normalized).matches()) {
                canonical.add(normalized);
            } else if (invalid.size() < MAX_ITEMS) {
                invalid.add(value == null ? "" : value);
            }
        }
        if (!invalid.isEmpty()) {
            throw new Invalid(Invalid.Reason.INVALID, invalid);
        }
        if (canonical.size() > MAX_ITEMS) {
            throw new Invalid(Invalid.Reason.TOO_MANY, List.of());
        }
        return new CfopFilter(List.copyOf(canonical));
    }

    /**
     * CF-RN-09: the old single {@code cfop} of the notes screen, as it always behaved - only digits, never checked; a
     * value without 4 digits just matches nothing. Blank (or without any digit) = no filter.
     */
    public static CfopFilter legacy(String raw) {
        String digits = normalize(raw);
        return digits.isEmpty() ? NONE : new CfopFilter(List.of(digits));
    }

    /**
     * CF-RN-09: {@code cfops} when it came filled (not null, not empty), else the old {@code cfop}.
     */
    public static CfopFilter resolve(Collection<String> cfops, String legacyCfop) {
        return cfops != null && !cfops.isEmpty() ? of(cfops) : legacy(legacyCfop);
    }

    /** CF-RN-09: whether {@link #resolve} falls back to the old field - logged, to know when it can go (B-CF-06). */
    public static boolean usesLegacy(Collection<String> cfops, String legacyCfop) {
        return (cfops == null || cfops.isEmpty()) && !normalize(legacyCfop).isEmpty();
    }

    public boolean isEmpty() {
        return cfops.isEmpty();
    }

    /** The canonical list (ascending, no duplicate). */
    public List<String> values() {
        return cfops;
    }

    /** What the query binds to {@code in (...)}: the values, or a sentinel for the empty filter (see {@link #SENTINEL}). */
    public List<String> valuesOrSentinel() {
        return cfops.isEmpty() ? SENTINEL : cfops;
    }

    /** CF-RN-06/21: at least one of {@code candidates} is in the filter; an empty filter matches everything. */
    public boolean matchesAny(Collection<String> candidates) {
        if (cfops.isEmpty()) {
            return true;
        }
        if (candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && cfops.contains(candidate.strip())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CfopFilter filter && cfops.equals(filter.cfops);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cfops);
    }

    @Override
    public String toString() {
        return "CfopFilter" + cfops;
    }

    /** The filter could not be read: which rule it broke and, for {@link Reason#INVALID}, the values as they came. */
    public static final class Invalid extends RuntimeException {

        public enum Reason { INVALID, TOO_MANY }

        private final Reason reason;
        private final List<String> invalidValues;

        Invalid(Reason reason, List<String> invalidValues) {
            super(reason.name(), null, false, false);
            this.reason = reason;
            this.invalidValues = List.copyOf(invalidValues);
        }

        public Reason reason() {
            return reason;
        }

        /** At most {@link #MAX_ITEMS}, as they came (null as ""). */
        public List<String> invalidValues() {
            return invalidValues;
        }
    }
}
