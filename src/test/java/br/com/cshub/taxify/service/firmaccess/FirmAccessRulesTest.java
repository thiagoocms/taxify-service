package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmNotificationType;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F1-RN-05, F1-RN-07, F1-RN-12 e F1-RN-16 com os casos numéricos da spec (datas de Recife). */
class FirmAccessRulesTest {

    private static final UUID FIRM = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final LocalDate NOV_26 = LocalDate.of(2026, 11, 26);
    private static final LocalDate DEC_09 = LocalDate.of(2026, 12, 9);
    private static final LocalDate DEC_10 = LocalDate.of(2026, 12, 10);

    private static FirmSubscription subscription(FirmSubscriptionStatus status) {
        FirmSubscription subscription = new FirmSubscription();
        subscription.setAccountingFirmId(FIRM);
        subscription.setStatus(status);
        return subscription;
    }

    private static FirmSubscription trialUntil(LocalDate end) {
        FirmSubscription subscription = subscription(FirmSubscriptionStatus.TRIAL);
        subscription.setTrialEndsOn(end);
        return subscription;
    }

    /** CT1-02 / CA1-02: verificado em 26/11, 14 dias com o dia da verificação como dia 1 → último dia 09/12. */
    @Test
    void theVerificationDayIsDayOne() {
        assertThat(FirmAccessRules.trialEndsOn(NOV_26, 14)).isEqualTo(DEC_09);
        assertThat(FirmAccessRules.trialEndsOn(NOV_26, 1)).isEqualTo(NOV_26);
        assertThatThrownBy(() -> FirmAccessRules.trialEndsOn(NOV_26, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** CA1-08 / CT1-15: o último dia ainda escreve; no dia seguinte é BLOCKED por TRIAL_EXPIRED desde 10/12, sem agendador. */
    @Test
    void theTrialLastsTheWholeLastDayAndBlocksTheNextOne() {
        FirmAccess lastDay = FirmAccessRules.evaluate(FIRM, trialUntil(DEC_09), false, DEC_09);
        assertThat(lastDay.status()).isEqualTo(FirmSubscriptionStatus.TRIAL);
        assertThat(lastDay.readOnly()).isFalse();
        assertThat(lastDay.trialLimited()).isTrue();

        FirmAccess nextDay = FirmAccessRules.evaluate(FIRM, trialUntil(DEC_09), false, DEC_10);
        assertThat(nextDay.status()).isEqualTo(FirmSubscriptionStatus.BLOCKED);
        assertThat(nextDay.readOnly()).isTrue();
        assertThat(nextDay.reason()).isEqualTo(BlockedReason.TRIAL_EXPIRED);
        assertThat(nextDay.blockedSince()).isEqualTo(DEC_10);
        assertThat(nextDay.allowsAutomaticRoutines()).isFalse();
    }

    /** CA1-22 / F1-RN-16: "faltam 14 dias" em 26/11 e "termina hoje" (1) em 09/12. */
    @Test
    void daysLeftCountTodayAsADay() {
        assertThat(FirmAccessRules.evaluate(FIRM, trialUntil(DEC_09), false, NOV_26).trialDaysLeft()).isEqualTo(14);
        assertThat(FirmAccessRules.evaluate(FIRM, trialUntil(DEC_09), false, DEC_09).trialDaysLeft()).isEqualTo(1);
    }

    /** CA1-11 / CT1-19: bloqueado desde 10/12, aberto em 20/12 → capturas paradas há 10 dias. */
    @Test
    void capturesStoppedDaysCountFromTheBlock() {
        FirmSubscription blocked = subscription(FirmSubscriptionStatus.BLOCKED);
        blocked.setBlockedReason(BlockedReason.TRIAL_EXPIRED);
        blocked.setBlockedSince(DEC_10);

        FirmAccess access = FirmAccessRules.evaluate(FIRM, blocked, false, LocalDate.of(2026, 12, 20));

        assertThat(access.capturesStoppedDays()).isEqualTo(10);
        assertThat(access.reason()).isEqualTo(BlockedReason.TRIAL_EXPIRED);
        // calculated on the fly too (stored TRIAL, scheduler not run yet)
        assertThat(FirmAccessRules.evaluate(FIRM, trialUntil(DEC_09), false, LocalDate.of(2026, 12, 20)).capturesStoppedDays())
                .isEqualTo(10);
    }

    /** CT1-18: suspensão e cancelamento são somente leitura, igual ao teste vencido. */
    @Test
    void suspendedAndCanceledAreReadOnly() {
        FirmSubscription suspended = subscription(FirmSubscriptionStatus.SUSPENDED);
        suspended.setBlockedSince(NOV_26);
        assertThat(FirmAccessRules.evaluate(FIRM, suspended, false, NOV_26).readOnly()).isTrue();
        assertThat(FirmAccessRules.evaluate(FIRM, suspended, false, NOV_26).reason()).isEqualTo(BlockedReason.MANUAL);
        assertThat(FirmAccessRules.evaluate(FIRM, subscription(FirmSubscriptionStatus.CANCELED), false, NOV_26).readOnly()).isTrue();
        assertThat(FirmAccessRules.evaluate(FIRM, subscription(FirmSubscriptionStatus.PENDING_VERIFICATION), false, NOV_26)
                .pendingVerification()).isTrue();
    }

    /** F1-RN-07 / CT1-31: ACTIVE sem fim é total e sem limite; com access_until vencido vira BLOCKED no dia seguinte. */
    @Test
    void activeLastsUntilAccessUntil() {
        FirmSubscription released = subscription(FirmSubscriptionStatus.ACTIVE);
        FirmAccess unlimited = FirmAccessRules.evaluate(FIRM, released, false, NOV_26);
        assertThat(unlimited.readOnly()).isFalse();
        assertThat(unlimited.trialLimited()).isFalse();
        assertThat(unlimited.allowsAutomaticRoutines()).isTrue();

        released.setAccessUntil(DEC_09);
        assertThat(FirmAccessRules.evaluate(FIRM, released, false, DEC_09).status()).isEqualTo(FirmSubscriptionStatus.ACTIVE);
        FirmAccess expired = FirmAccessRules.evaluate(FIRM, released, false, DEC_10);
        assertThat(expired.status()).isEqualTo(FirmSubscriptionStatus.BLOCKED);
        assertThat(expired.reason()).isEqualTo(BlockedReason.ACCESS_EXPIRED);
        assertThat(expired.blockedSince()).isEqualTo(DEC_10);
    }

    /** CA1-18 / CT1-32: o escritório da CS Hub, mesmo sem linha, nunca é bloqueado nem limitado e roda as rotinas. */
    @Test
    void thePlatformFirmIsExempt() {
        FirmAccess exempt = FirmAccessRules.evaluate(FIRM, null, true, DEC_10);
        assertThat(exempt.exempt()).isTrue();
        assertThat(exempt.readOnly()).isFalse();
        assertThat(exempt.trialLimited()).isFalse();
        assertThat(exempt.allowsAutomaticRoutines()).isTrue();

        FirmAccess expiredTrialButExempt = FirmAccessRules.evaluate(FIRM, trialUntil(DEC_09), true, DEC_10);
        assertThat(expiredTrialButExempt.readOnly()).isFalse();
    }

    /** F1-RN-07: um escritório sem firm_subscription é tratado como SUSPENDED. */
    @Test
    void aFirmWithoutSubscriptionIsSuspended() {
        FirmAccess access = FirmAccessRules.evaluate(FIRM, null, false, NOV_26);
        assertThat(access.status()).isEqualTo(FirmSubscriptionStatus.SUSPENDED);
        assertThat(access.readOnly()).isTrue();
        assertThat(access.missingSubscription()).isTrue();
    }

    /** CT1-37 / CT1-39: D3 em 06/12, D1 em 08/12, D0 em 09/12; nos outros dias (inclusive os que passaram) nada. */
    @Test
    void noticesAreDueOnlyOnTheirOwnDay() {
        assertThat(FirmAccessRules.noticeDue(DEC_09, LocalDate.of(2026, 12, 6))).contains(FirmNotificationType.TRIAL_D3);
        assertThat(FirmAccessRules.noticeDue(DEC_09, LocalDate.of(2026, 12, 8))).contains(FirmNotificationType.TRIAL_D1);
        assertThat(FirmAccessRules.noticeDue(DEC_09, DEC_09)).contains(FirmNotificationType.TRIAL_D0);
        assertThat(FirmAccessRules.noticeDue(DEC_09, LocalDate.of(2026, 12, 7))).isEmpty();
        assertThat(FirmAccessRules.noticeDue(DEC_09, DEC_10)).isEmpty();
        assertThat(FirmAccessRules.noticeDue(null, DEC_09)).isEmpty();
    }
}
