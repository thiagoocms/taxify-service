package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.DocumentRepository;
import br.com.cshub.taxify.repository.EmailVerificationRepository;
import br.com.cshub.taxify.repository.FirmNotificationRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionEventRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.TermsAcceptanceRepository;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Convention 3.8.8: each daily routine of the firm access has its own switch (TAXIFY_*_ENABLED, on by default). */
class FirmAccessSchedulersSwitchTest {

    private final FirmSubscriptionRepository subscriptions = mock(FirmSubscriptionRepository.class);
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 12, 3, 10, 0));

    private FirmAccessScheduler access(SaasProperties properties) {
        return new FirmAccessScheduler(subscriptions, mock(FirmAccessService.class), mock(FirmEventService.class),
                mock(PlatformTransactionManager.class), mock(AccountingFirmRepository.class), properties);
    }

    private UnverifiedSignupPurge purge(SaasProperties properties) {
        return new UnverifiedSignupPurge(subscriptions, mock(TermsAcceptanceRepository.class), mock(EmailVerificationRepository.class),
                mock(FirmNotificationRepository.class), mock(FirmSubscriptionEventRepository.class), mock(UserRepository.class),
                mock(UserCompanyRepository.class), mock(DocumentRepository.class), mock(AccountingFirmRepository.class),
                mock(FirmEventService.class), properties, clock, mock(PlatformTransactionManager.class),
                mock(UserOwnedRowsPurge.class));
    }

    private TrialNoticeScheduler notices(SaasProperties properties) {
        return new TrialNoticeScheduler(subscriptions, mock(FirmNotificationRepository.class), mock(AccountingFirmRepository.class),
                mock(UserRepository.class), mock(FirmAccessService.class), mock(MailSender.class), properties, clock);
    }

    @Test
    void switchedOffRoutinesDoNothing() {
        SaasProperties off = new SaasProperties();
        off.setAccessEnabled(false);
        off.setPurgeEnabled(false);
        off.setTrialNoticeEnabled(false);

        access(off).tick();
        purge(off).tick();
        notices(off).tick();

        verifyNoInteractions(subscriptions);
    }

    @Test
    void theRoutinesRunByDefault() {
        when(subscriptions.findByStatusInAndDeletedIsFalse(any())).thenReturn(List.of());
        SaasProperties defaults = new SaasProperties();

        access(defaults).tick();
        purge(defaults).tick();
        notices(defaults).tick();

        verify(subscriptions, times(3)).findByStatusInAndDeletedIsFalse(any());
    }
}
