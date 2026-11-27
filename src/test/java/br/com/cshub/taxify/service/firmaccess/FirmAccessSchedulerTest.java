package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F1-RN-07: o agendador diário só grava o BLOCKED (com blocked_since e o evento) que já vale pelo cálculo. */
class FirmAccessSchedulerTest {

    @Test
    void recordsTheExpiredTrialAndAccessButNotTheExemptFirm() {
        FirmSubscriptionRepository repository = mock(FirmSubscriptionRepository.class);
        FirmAccessService access = mock(FirmAccessService.class);
        FirmEventService events = mock(FirmEventService.class);
        UUID exemptId = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 12, 10);
        when(access.evaluate(any(), any())).thenAnswer(invocation -> FirmAccessRules.evaluate(invocation.getArgument(0),
                invocation.getArgument(1), exemptId.equals(invocation.getArgument(0)), today));

        FirmSubscription expiredTrial = subscription(UUID.randomUUID(), FirmSubscriptionStatus.TRIAL);
        expiredTrial.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        FirmSubscription runningTrial = subscription(UUID.randomUUID(), FirmSubscriptionStatus.TRIAL);
        runningTrial.setTrialEndsOn(LocalDate.of(2026, 12, 10));
        FirmSubscription expiredRelease = subscription(UUID.randomUUID(), FirmSubscriptionStatus.ACTIVE);
        expiredRelease.setAccessUntil(LocalDate.of(2026, 12, 1));
        FirmSubscription exempt = subscription(exemptId, FirmSubscriptionStatus.TRIAL);
        exempt.setTrialEndsOn(LocalDate.of(2026, 1, 1));
        when(repository.findByStatusInAndDeletedIsFalse(List.of(FirmSubscriptionStatus.TRIAL, FirmSubscriptionStatus.ACTIVE)))
                .thenReturn(List.of(expiredTrial, runningTrial, expiredRelease, exempt));
        for (FirmSubscription subscription : List.of(expiredTrial, runningTrial, expiredRelease, exempt)) {
            when(repository.findForUpdate(subscription.getAccountingFirmId())).thenReturn(java.util.Optional.of(subscription));
        }
        AccountingFirmRepository firms = mock(AccountingFirmRepository.class);

        int recorded = new FirmAccessScheduler(repository, access, events, mock(PlatformTransactionManager.class), firms,
                new br.com.cshub.taxify.config.SaasProperties()).recordExpired();

        assertThat(recorded).isEqualTo(2);
        assertThat(expiredTrial.getStatus()).isEqualTo(FirmSubscriptionStatus.BLOCKED);
        assertThat(expiredTrial.getBlockedSince()).isEqualTo(today);
        assertThat(expiredTrial.getBlockedReason()).isEqualTo(BlockedReason.TRIAL_EXPIRED);
        assertThat(expiredRelease.getBlockedReason()).isEqualTo(BlockedReason.ACCESS_EXPIRED);
        assertThat(expiredRelease.getBlockedSince()).isEqualTo(LocalDate.of(2026, 12, 2));
        assertThat(runningTrial.getStatus()).isEqualTo(FirmSubscriptionStatus.TRIAL);
        assertThat(exempt.getStatus()).isEqualTo(FirmSubscriptionStatus.TRIAL);
        verify(events, times(2)).record(any(), eq(FirmEventType.BLOCKED), any(), any(), any(), eq(FirmEventService.SYSTEM));
        // the firm row is locked before the subscription is read again
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(firms, repository);
        order.verify(firms).lock(expiredTrial.getAccountingFirmId());
        order.verify(repository).findForUpdate(expiredTrial.getAccountingFirmId());
    }

    /** Review item 3: an extension saved by the platform admin after the candidates were read wins - nothing is written. */
    @Test
    void anExtensionMadeMeanwhileIsNotOverwritten() {
        FirmSubscriptionRepository repository = mock(FirmSubscriptionRepository.class);
        FirmAccessService access = mock(FirmAccessService.class);
        FirmEventService events = mock(FirmEventService.class);
        LocalDate today = LocalDate.of(2026, 12, 10);
        when(access.evaluate(any(), any())).thenAnswer(invocation ->
                FirmAccessRules.evaluate(invocation.getArgument(0), invocation.getArgument(1), false, today));
        UUID firmId = UUID.randomUUID();
        FirmSubscription stale = subscription(firmId, FirmSubscriptionStatus.TRIAL);
        stale.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        FirmSubscription extended = subscription(firmId, FirmSubscriptionStatus.TRIAL);
        extended.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        when(repository.findByStatusInAndDeletedIsFalse(any())).thenReturn(List.of(stale));
        when(repository.findForUpdate(firmId)).thenReturn(java.util.Optional.of(extended));

        int recorded = new FirmAccessScheduler(repository, access, events, mock(PlatformTransactionManager.class),
                mock(AccountingFirmRepository.class), new br.com.cshub.taxify.config.SaasProperties()).recordExpired();

        assertThat(recorded).isZero();
        assertThat(extended.getStatus()).isEqualTo(FirmSubscriptionStatus.TRIAL);
        verify(repository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verifyNoInteractions(events);
    }

    private static FirmSubscription subscription(UUID firmId, FirmSubscriptionStatus status) {
        FirmSubscription subscription = new FirmSubscription();
        subscription.setAccountingFirmId(firmId);
        subscription.setStatus(status);
        return subscription;
    }
}
