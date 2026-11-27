package br.com.cshub.taxify.service.taxclearance;

import br.com.cshub.taxify.domain.taxclearance.StateTaxClearance;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * When a company's state certidão has to be issued again: there is none, it has no validity, or it expires in
 * {@value #RENEW_DAYS} days or less. The same rule drives the weekly job, the "a vencer" pendency and the carteira's seal.
 */
public final class TaxClearanceRenewalRule {

    /** Issue again when this many days (or fewer) are left. */
    public static final int RENEW_DAYS = 15;

    /** The certidão as the carteira shows it. */
    public enum Status {
        /** No certidão yet. */
        SEM_CERTIDAO,
        VALIDA,
        /** Valid, {@value #RENEW_DAYS} days or less left - time to issue the next one. */
        A_VENCER,
        VENCIDA
    }

    private TaxClearanceRenewalRule() {
    }

    public static boolean due(StateTaxClearance latest, LocalDate today) {
        Status status = status(latest, today);
        return status != Status.VALIDA;
    }

    public static Status status(StateTaxClearance latest, LocalDate today) {
        if (latest == null || latest.getValidUntil() == null) {
            return Status.SEM_CERTIDAO;
        }
        long left = daysLeft(latest, today);
        if (left < 0) {
            return Status.VENCIDA;
        }
        return left <= RENEW_DAYS ? Status.A_VENCER : Status.VALIDA;
    }

    /** Days until the certidão expires (0 = expires today, negative = expired). */
    public static long daysLeft(StateTaxClearance clearance, LocalDate today) {
        return ChronoUnit.DAYS.between(today, clearance.getValidUntil());
    }
}
