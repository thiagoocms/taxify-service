package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * As ações do admin da plataforma sobre a assinatura de um escritório (F1-RN-14), como regra pura: dado o estado
 * gravado e o pedido, muda a assinatura ou recusa. Toda ação também conta como revisão do escritório migrado.
 */
public final class FirmAccessActions {

    private FirmAccessActions() {
    }

    public enum Action {
        EXTEND_TRIAL("extend-trial", FirmEventType.TRIAL_EXTENDED),
        CONVERT_TO_TRIAL("convert-to-trial", FirmEventType.CONVERTED_TO_TRIAL),
        RELEASE("release", FirmEventType.RELEASED),
        SUSPEND("suspend", FirmEventType.SUSPENDED),
        REACTIVATE("reactivate", FirmEventType.REACTIVATED),
        CANCEL("cancel", FirmEventType.CANCELED),
        MARK_REVIEWED("mark-reviewed", FirmEventType.REVIEWED);

        private final String path;
        private final FirmEventType event;

        Action(String path, FirmEventType event) {
            this.path = path;
            this.event = event;
        }

        public String path() {
            return path;
        }

        public FirmEventType event() {
            return event;
        }

        public static Action fromPath(String value) {
            String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            for (Action action : values()) {
                if (action.path.equals(normalized)) {
                    return action;
                }
            }
            return null;
        }
    }

    /** Por que uma ação foi recusada - vira 400/409 com o código {@code error.platform.*}. */
    public static final class Refused extends RuntimeException {
        private final String code;
        private final boolean badRequest;

        Refused(String code, boolean badRequest) {
            super(code);
            this.code = code;
            this.badRequest = badRequest;
        }

        public String code() {
            return code;
        }

        public boolean badRequest() {
            return badRequest;
        }
    }

    /**
     * @param previousStatus no {@code reactivate}: a situação de antes da suspensão (do evento SUSPENDED), ou nula
     */
    public static void apply(FirmSubscription subscription, Action action, LocalDate trialEndsOn, LocalDate accessUntil,
                             String reason, LocalDate today, int trialDays, FirmSubscriptionStatus previousStatus,
                             BlockedReason previousReason, String actor, LocalDateTime now) {
        FirmSubscriptionStatus current = subscription.getStatus();
        switch (action) {
            case EXTEND_TRIAL -> {
                // nova data >= hoje; volta a TRIAL se estava BLOCKED por TRIAL_EXPIRED
                boolean trial = current == FirmSubscriptionStatus.TRIAL
                        || (current == FirmSubscriptionStatus.BLOCKED && subscription.getBlockedReason() == BlockedReason.TRIAL_EXPIRED);
                if (!trial) {
                    throw new Refused("error.platform.action.invalid", false);
                }
                requireFutureDate(trialEndsOn, today, true);
                toTrial(subscription, trialEndsOn);
            }
            case CONVERT_TO_TRIAL -> {
                // ACTIVE (migrado ou liberado) → TRIAL; sem data, o mesmo cálculo da F1-RN-05 (hoje + dias − 1)
                boolean active = current == FirmSubscriptionStatus.ACTIVE
                        || (current == FirmSubscriptionStatus.BLOCKED && subscription.getBlockedReason() == BlockedReason.ACCESS_EXPIRED);
                if (!active) {
                    throw new Refused("error.platform.action.invalid", false);
                }
                LocalDate end = trialEndsOn == null ? FirmAccessRules.trialEndsOn(today, trialDays) : trialEndsOn;
                requireFutureDate(end, today, true);
                subscription.setAccessUntil(null);
                toTrial(subscription, end);
            }
            case RELEASE -> {
                if (current == FirmSubscriptionStatus.PENDING_VERIFICATION) {
                    throw new Refused("error.platform.action.invalid", false);
                }
                requireFutureDate(accessUntil, today, false);
                subscription.setStatus(FirmSubscriptionStatus.ACTIVE);
                subscription.setAccessUntil(accessUntil);
                clearBlock(subscription);
            }
            case SUSPEND -> {
                if (reason == null || reason.isBlank()) {
                    throw new Refused("error.platform.reason.required", true);
                }
                if (current == FirmSubscriptionStatus.PENDING_VERIFICATION || current == FirmSubscriptionStatus.SUSPENDED
                        || current == FirmSubscriptionStatus.CANCELED) {
                    throw new Refused("error.platform.action.invalid", false);
                }
                subscription.setStatus(FirmSubscriptionStatus.SUSPENDED);
                subscription.setBlockedSince(today);
                subscription.setBlockedReason(BlockedReason.MANUAL);
            }
            case REACTIVATE -> {
                // SUSPENDED → TRIAL ou ACTIVE, conforme as datas: volta ao teste quem estava em teste antes da suspensão
                if (current != FirmSubscriptionStatus.SUSPENDED) {
                    throw new Refused("error.platform.action.invalid", false);
                }
                boolean wasTrial = previousStatus != null
                        ? previousStatus == FirmSubscriptionStatus.TRIAL
                        || (previousStatus == FirmSubscriptionStatus.BLOCKED && previousReason == BlockedReason.TRIAL_EXPIRED)
                        : subscription.getTrialEndsOn() != null;
                subscription.setStatus(wasTrial ? FirmSubscriptionStatus.TRIAL : FirmSubscriptionStatus.ACTIVE);
                LocalDate suspendedSince = subscription.getBlockedSince();
                clearBlock(subscription);
                // still read-only (the trial or the release expired during the suspension): the block goes on from the
                // suspension - its start is kept, so the later recovery covers it (QA-02)
                if (FirmAccessRules.evaluate(subscription.getAccountingFirmId(), subscription, false, today).readOnly()) {
                    subscription.setBlockedSince(suspendedSince);
                }
            }
            case CANCEL -> {
                if (current == FirmSubscriptionStatus.CANCELED || current == FirmSubscriptionStatus.PENDING_VERIFICATION) {
                    throw new Refused("error.platform.action.invalid", false);
                }
                subscription.setStatus(FirmSubscriptionStatus.CANCELED);
                subscription.setBlockedSince(today);
                subscription.setBlockedReason(BlockedReason.MANUAL);
            }
            case MARK_REVIEWED -> {
                // mantém como está
            }
        }
        subscription.setReviewedAt(now);
        subscription.setReviewedBy(actor == null ? null : actor.length() > 50 ? actor.substring(0, 50) : actor);
    }

    private static void toTrial(FirmSubscription subscription, LocalDate trialEndsOn) {
        subscription.setStatus(FirmSubscriptionStatus.TRIAL);
        subscription.setTrialEndsOn(trialEndsOn);
        clearBlock(subscription);
    }

    private static void clearBlock(FirmSubscription subscription) {
        subscription.setBlockedSince(null);
        subscription.setBlockedReason(null);
    }

    private static void requireFutureDate(LocalDate date, LocalDate today, boolean required) {
        if (date == null) {
            if (required) {
                throw new Refused("error.platform.date.required", true);
            }
            return;
        }
        if (date.isBefore(today)) {
            throw new Refused("error.platform.date.invalid", true);
        }
    }
}
