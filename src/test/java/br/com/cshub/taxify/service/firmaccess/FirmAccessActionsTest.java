package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.service.firmaccess.FirmAccessActions.Action;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F1-RN-14 com os casos CA1-20, CT1-34 e CT1-35. */
class FirmAccessActionsTest {

    private static final LocalDate NOV_26 = LocalDate.of(2026, 11, 26);
    private static final LocalDateTime NOW = NOV_26.atTime(10, 0);

    private static FirmSubscription migrated() {
        FirmSubscription subscription = new FirmSubscription();
        subscription.setStatus(FirmSubscriptionStatus.ACTIVE);
        subscription.setMigrated(true);
        return subscription;
    }

    private static void apply(FirmSubscription subscription, Action action, LocalDate trialEndsOn, LocalDate accessUntil,
                              String reason, LocalDate today) {
        FirmAccessActions.apply(subscription, action, trialEndsOn, accessUntil, reason, today, 14, null, null, "csadmin",
                today.atTime(10, 0));
    }

    /** CA1-20 / CT1-34: convert-to-trial sem data em 26/11 → TRIAL até 09/12 e revisado pelo admin. */
    @Test
    void convertToTrialWithoutDateUsesTheTrialLength() {
        FirmSubscription subscription = migrated();

        apply(subscription, Action.CONVERT_TO_TRIAL, null, null, null, NOV_26);

        assertThat(subscription.getStatus()).isEqualTo(FirmSubscriptionStatus.TRIAL);
        assertThat(subscription.getTrialEndsOn()).isEqualTo(LocalDate.of(2026, 12, 9));
        assertThat(subscription.getReviewedAt()).isEqualTo(NOW);
        assertThat(subscription.getReviewedBy()).isEqualTo("csadmin");
    }

    /** CA1-20 / CT1-35: extend-trial até 20/12 num teste vencido volta para TRIAL. */
    @Test
    void extendTrialBringsAnExpiredTrialBack() {
        FirmSubscription expired = new FirmSubscription();
        expired.setStatus(FirmSubscriptionStatus.BLOCKED);
        expired.setBlockedReason(BlockedReason.TRIAL_EXPIRED);
        expired.setBlockedSince(LocalDate.of(2026, 12, 10));
        expired.setTrialEndsOn(LocalDate.of(2026, 12, 9));

        apply(expired, Action.EXTEND_TRIAL, LocalDate.of(2026, 12, 20), null, null, LocalDate.of(2026, 12, 12));

        assertThat(expired.getStatus()).isEqualTo(FirmSubscriptionStatus.TRIAL);
        assertThat(expired.getTrialEndsOn()).isEqualTo(LocalDate.of(2026, 12, 20));
        assertThat(expired.getBlockedSince()).isNull();
        assertThat(expired.getBlockedReason()).isNull();
    }

    @Test
    void extendTrialNeedsADateNotInThePast() {
        FirmSubscription trial = new FirmSubscription();
        trial.setStatus(FirmSubscriptionStatus.TRIAL);
        trial.setTrialEndsOn(NOV_26);

        assertThatThrownBy(() -> apply(trial, Action.EXTEND_TRIAL, null, null, null, NOV_26))
                .isInstanceOfSatisfying(FirmAccessActions.Refused.class, refused -> assertThat(refused.code()).isEqualTo("error.platform.date.required"));
        assertThatThrownBy(() -> apply(trial, Action.EXTEND_TRIAL, NOV_26.minusDays(1), null, null, NOV_26))
                .isInstanceOfSatisfying(FirmAccessActions.Refused.class, refused -> assertThat(refused.code()).isEqualTo("error.platform.date.invalid"));
        // an ACTIVE firm is converted, not extended
        assertThatThrownBy(() -> apply(migrated(), Action.EXTEND_TRIAL, NOV_26, null, null, NOV_26))
                .isInstanceOf(FirmAccessActions.Refused.class);
    }

    /** CT1-34: suspend sem motivo = 400; com motivo, SUSPENDED desde hoje, motivo MANUAL. */
    @Test
    void suspendNeedsAReason() {
        FirmSubscription subscription = migrated();
        assertThatThrownBy(() -> apply(subscription, Action.SUSPEND, null, null, " ", NOV_26))
                .isInstanceOfSatisfying(FirmAccessActions.Refused.class, refused -> {
                    assertThat(refused.code()).isEqualTo("error.platform.reason.required");
                    assertThat(refused.badRequest()).isTrue();
                });

        apply(subscription, Action.SUSPEND, null, null, "sem contrato", NOV_26);
        assertThat(subscription.getStatus()).isEqualTo(FirmSubscriptionStatus.SUSPENDED);
        assertThat(subscription.getBlockedSince()).isEqualTo(NOV_26);
        assertThat(subscription.getBlockedReason()).isEqualTo(BlockedReason.MANUAL);
    }

    /** CT1-34: mark-reviewed mantém a liberação e marca a revisão. */
    @Test
    void markReviewedKeepsTheFirmReleased() {
        FirmSubscription subscription = migrated();

        apply(subscription, Action.MARK_REVIEWED, null, null, null, NOV_26);

        assertThat(subscription.getStatus()).isEqualTo(FirmSubscriptionStatus.ACTIVE);
        assertThat(subscription.getAccessUntil()).isNull();
        assertThat(subscription.getReviewedAt()).isNotNull();
    }

    @Test
    void releaseWithAnOptionalEndDate() {
        FirmSubscription trial = new FirmSubscription();
        trial.setStatus(FirmSubscriptionStatus.TRIAL);
        trial.setTrialEndsOn(NOV_26);

        apply(trial, Action.RELEASE, null, LocalDate.of(2027, 1, 31), null, NOV_26);

        assertThat(trial.getStatus()).isEqualTo(FirmSubscriptionStatus.ACTIVE);
        assertThat(trial.getAccessUntil()).isEqualTo(LocalDate.of(2027, 1, 31));
        FirmSubscription pending = new FirmSubscription();
        pending.setStatus(FirmSubscriptionStatus.PENDING_VERIFICATION);
        assertThatThrownBy(() -> apply(pending, Action.RELEASE, null, null, null, NOV_26)).isInstanceOf(FirmAccessActions.Refused.class);
    }

    /** reactivate: volta ao que era antes da suspensão - teste para quem estava em teste, ACTIVE para o liberado. */
    @Test
    void reactivateGoesBackToWhatItWasBeforeTheSuspension() {
        FirmSubscription wasTrial = new FirmSubscription();
        wasTrial.setStatus(FirmSubscriptionStatus.SUSPENDED);
        wasTrial.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        wasTrial.setBlockedSince(NOV_26);
        FirmAccessActions.apply(wasTrial, Action.REACTIVATE, null, null, null, NOV_26, 14,
                FirmSubscriptionStatus.TRIAL, null, "csadmin", NOW);
        assertThat(wasTrial.getStatus()).isEqualTo(FirmSubscriptionStatus.TRIAL);
        assertThat(wasTrial.getBlockedSince()).isNull();

        FirmSubscription wasReleased = new FirmSubscription();
        wasReleased.setStatus(FirmSubscriptionStatus.SUSPENDED);
        wasReleased.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        FirmAccessActions.apply(wasReleased, Action.REACTIVATE, null, null, null, NOV_26, 14,
                FirmSubscriptionStatus.ACTIVE, null, "csadmin", NOW);
        assertThat(wasReleased.getStatus()).isEqualTo(FirmSubscriptionStatus.ACTIVE);

        assertThatThrownBy(() -> apply(migrated(), Action.REACTIVATE, null, null, null, NOV_26))
                .isInstanceOf(FirmAccessActions.Refused.class);
    }

    /**
     * Review (a): suspended on 30/11 during a valid trial (until 09/12); the trial expires meanwhile; reactivated on
     * 15/12 it is still read-only and the block keeps starting on 30/11; extended on 04/01, the recovery covers
     * November and December - from the suspension, not from the trial's end.
     */
    @Test
    void aReactivationThatStaysReadOnlyKeepsTheSuspensionStart() {
        FirmSubscription trial = new FirmSubscription();
        trial.setStatus(FirmSubscriptionStatus.TRIAL);
        trial.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        LocalDate nov30 = LocalDate.of(2026, 11, 30);
        apply(trial, Action.SUSPEND, null, null, "sem contrato", nov30);
        assertThat(trial.getBlockedSince()).isEqualTo(nov30);

        LocalDate dec15 = LocalDate.of(2026, 12, 15);
        FirmAccessActions.apply(trial, Action.REACTIVATE, null, null, null, dec15, 14,
                FirmSubscriptionStatus.TRIAL, null, "csadmin", dec15.atTime(9, 0));
        assertThat(trial.getStatus()).isEqualTo(FirmSubscriptionStatus.TRIAL);
        br.com.cshub.taxify.service.firmaccess.FirmAccess stillBlocked =
                br.com.cshub.taxify.service.firmaccess.FirmAccessRules.evaluate(null, trial, false, dec15);
        assertThat(stillBlocked.readOnly()).isTrue();
        assertThat(stillBlocked.blockedSince()).isEqualTo(nov30);

        LocalDate jan4 = LocalDate.of(2027, 1, 4);
        br.com.cshub.taxify.service.firmaccess.FirmAccess before =
                br.com.cshub.taxify.service.firmaccess.FirmAccessRules.evaluate(null, trial, false, jan4);
        apply(trial, Action.EXTEND_TRIAL, LocalDate.of(2027, 1, 20), null, null, jan4);
        assertThat(br.com.cshub.taxify.service.firmaccess.FirmAccessRules.evaluate(null, trial, false, jan4).readOnly()).isFalse();
        assertThat(before.blockedSince()).isEqualTo(nov30);
        assertThat(br.com.cshub.taxify.service.firmaccess.RecoveryPlan.plan(before.blockedSince(), jan4, 3).nfeMonths())
                .containsExactly(java.time.YearMonth.of(2026, 11), java.time.YearMonth.of(2026, 12));
    }

    /** A reactivation back to full access still clears the block. */
    @Test
    void aReactivationToFullAccessClearsTheBlock() {
        FirmSubscription trial = new FirmSubscription();
        trial.setStatus(FirmSubscriptionStatus.TRIAL);
        trial.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        apply(trial, Action.SUSPEND, null, null, "conferência", NOV_26);
        FirmAccessActions.apply(trial, Action.REACTIVATE, null, null, null, LocalDate.of(2026, 12, 1), 14,
                FirmSubscriptionStatus.TRIAL, null, "csadmin", NOW);
        assertThat(trial.getBlockedSince()).isNull();
    }

    @Test
    void cancelIsReadOnlyFromToday() {
        FirmSubscription subscription = migrated();
        apply(subscription, Action.CANCEL, null, null, null, NOV_26);
        assertThat(subscription.getStatus()).isEqualTo(FirmSubscriptionStatus.CANCELED);
        assertThat(subscription.getBlockedSince()).isEqualTo(NOV_26);
        assertThatThrownBy(() -> apply(subscription, Action.CANCEL, null, null, null, NOV_26)).isInstanceOf(FirmAccessActions.Refused.class);
    }

    @Test
    void actionsAreReadFromThePath() {
        assertThat(Action.fromPath("extend-trial")).isEqualTo(Action.EXTEND_TRIAL);
        assertThat(Action.fromPath("Mark-Reviewed")).isEqualTo(Action.MARK_REVIEWED);
        assertThat(Action.fromPath("delete")).isNull();
    }
}
