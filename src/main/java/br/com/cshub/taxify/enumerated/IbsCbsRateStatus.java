package br.com.cshub.taxify.enumerated;

import java.util.Locale;
import java.util.Optional;

/**
 * Status of the IBS/CBS reference rates of one year (spec 2026-11-27, F1a-0, AL-RN-01, decision P-05).
 * <ul>
 *   <li>{@link #LAW} - an official act has been published that sets the rates of the year;</li>
 *   <li>{@link #PENDING} - waiting for the act (numbers may be there or not);</li>
 *   <li>{@link #ESTIMATE} - a study or projection number, with no act.</li>
 * </ul>
 * Only {@link #LAW} generates conferência findings (AL-RN-02).
 */
public enum IbsCbsRateStatus {
    LAW, PENDING, ESTIMATE;

    /** The status from the request body; empty for a missing or unknown value. */
    public static Optional<IbsCbsRateStatus> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** With LAW or ESTIMATE the three rates are mandatory; with PENDING each one is optional (AL-RN-03). */
    public boolean requiresAllRates() {
        return this != PENDING;
    }

    /** With LAW the legal basis and the link to the act are mandatory (AL-RN-03). */
    public boolean requiresLegalBasis() {
        return this == LAW;
    }
}
