package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmNotificationType;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Regras puras (sem I/O) da situação de acesso do escritório - F1-RN-05, F1-RN-07, F1-RN-12 e F1-RN-16. "Hoje" é
 * sempre a data de America/Recife, passada por quem chama (FirmAccessService usa o Clock de Recife).
 */
public final class FirmAccessRules {

    private FirmAccessRules() {
    }

    /**
     * F1-RN-05: o dia da verificação conta como dia 1 - verificado em 2026-11-26 com 14 dias, o último é 2026-12-09.
     */
    public static LocalDate trialEndsOn(LocalDate startDay, int trialDays) {
        if (trialDays < 1) {
            throw new IllegalArgumentException("trialDays must be >= 1");
        }
        return startDay.plusDays(trialDays - 1L);
    }

    /**
     * F1-RN-07: a situação efetiva de hoje. A passagem de TRIAL vencido e de ACTIVE com {@code access_until} vencido
     * para BLOCKED é calculada aqui, sem depender do agendador. O teste e a liberação valem até 23:59:59 do último dia.
     *
     * @param subscription a linha do escritório, ou nula (escritório sem linha: SUSPENDED, F1-RN-07)
     * @param exempt       é o escritório {@code PLATFORM_FIRM_ID} (F1-RN-12)
     */
    public static FirmAccess evaluate(UUID firmId, FirmSubscription subscription, boolean exempt, LocalDate today) {
        if (exempt) {
            return new FirmAccess(firmId, FirmSubscriptionStatus.ACTIVE, true, false, null, null,
                    subscription == null ? null : subscription.getTrialEndsOn(),
                    subscription == null ? null : subscription.getAccessUntil(), null, null, subscription == null);
        }
        if (subscription == null) {
            return blocked(firmId, FirmSubscriptionStatus.SUSPENDED, BlockedReason.MANUAL, null, null, null, today, true);
        }
        LocalDate trialEnd = subscription.getTrialEndsOn();
        LocalDate accessUntil = subscription.getAccessUntil();
        return switch (subscription.getStatus()) {
            case PENDING_VERIFICATION -> new FirmAccess(firmId, FirmSubscriptionStatus.PENDING_VERIFICATION, false, true,
                    null, null, trialEnd, accessUntil, null, null, false);
            case TRIAL -> {
                if (trialEnd != null && !today.isAfter(trialEnd)) {
                    int left = (int) ChronoUnit.DAYS.between(today, trialEnd) + 1;
                    yield new FirmAccess(firmId, FirmSubscriptionStatus.TRIAL, false, false, null, null, trialEnd,
                            accessUntil, left, null, false);
                }
                // the earliest known start: a suspension during a valid trial that expired meanwhile keeps its date
                LocalDate since = earliest(subscription.getBlockedSince(), trialEnd == null ? null : trialEnd.plusDays(1));
                yield blocked(firmId, FirmSubscriptionStatus.BLOCKED, BlockedReason.TRIAL_EXPIRED, since, trialEnd,
                        accessUntil, today, false);
            }
            case ACTIVE, PAST_DUE -> {
                if (accessUntil == null || !today.isAfter(accessUntil)) {
                    yield new FirmAccess(firmId, subscription.getStatus(), false, false, null, null, trialEnd,
                            accessUntil, null, null, false);
                }
                yield blocked(firmId, FirmSubscriptionStatus.BLOCKED, BlockedReason.ACCESS_EXPIRED,
                        earliest(subscription.getBlockedSince(), accessUntil.plusDays(1)), trialEnd, accessUntil, today, false);
            }
            case BLOCKED, SUSPENDED, CANCELED -> blocked(firmId, subscription.getStatus(),
                    subscription.getBlockedReason() == null ? BlockedReason.MANUAL : subscription.getBlockedReason(),
                    subscription.getBlockedSince(), trialEnd, accessUntil, today, false);
        };
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    private static FirmAccess blocked(UUID firmId, FirmSubscriptionStatus status, BlockedReason reason, LocalDate since,
                                      LocalDate trialEnd, LocalDate accessUntil, LocalDate today, boolean missing) {
        Integer stoppedDays = since == null ? null : (int) Math.max(0, ChronoUnit.DAYS.between(since, today));
        return new FirmAccess(firmId, status, false, true, reason, since, trialEnd, accessUntil, null, stoppedDays, missing);
    }

    /**
     * F1-RN-16: o aviso que cabe hoje para um teste que termina em {@code trialEndsOn} - D3 quando faltam 3 dias, D1
     * quando falta 1 e D0 no próprio dia. Fora desses dias, nenhum (um aviso cuja data passou não sai atrasado).
     */
    public static Optional<FirmNotificationType> noticeDue(LocalDate trialEndsOn, LocalDate today) {
        if (trialEndsOn == null) {
            return Optional.empty();
        }
        long daysBefore = ChronoUnit.DAYS.between(today, trialEndsOn);
        for (FirmNotificationType type : FirmNotificationType.values()) {
            if (type.daysBefore() == daysBefore) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
