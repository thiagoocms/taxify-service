package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.certificate.FirmCertificate;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.repository.FirmCertificateRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CA1-12 / CT1-20 / CT1-22 (F1-RN-10): a captura automática do e-Fisco para com o escritório e volta sozinha. */
class EfiscoSchedulerBlockedTest {

    @Test
    void theEfiscoSyncSkipsTheBlockedFirmAndRunsItAgainAfterTheExtension() {
        AccountingFirm blockedFirm = new AccountingFirm(UUID.randomUUID());
        AccountingFirm activeFirm = new AccountingFirm(UUID.randomUUID());
        FirmSubscription blocked = new FirmSubscription();
        blocked.setAccountingFirmId(blockedFirm.getId());
        blocked.setStatus(FirmSubscriptionStatus.TRIAL);
        blocked.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        FirmSubscription active = new FirmSubscription();
        active.setAccountingFirmId(activeFirm.getId());
        active.setStatus(FirmSubscriptionStatus.ACTIVE);
        FirmCertificateRepository certificates = mock(FirmCertificateRepository.class);
        when(certificates.findAllActive()).thenReturn(List.of(certificate(blockedFirm), certificate(activeFirm)));
        EfiscoSyncService syncService = mock(EfiscoSyncService.class);
        when(syncService.syncFirm(any(), any())).thenReturn(new EfiscoSyncService.SyncResult("OK", "", List.of()));
        EfiscoSyncScheduler scheduler = new EfiscoSyncScheduler(certificates, syncService, true,
                FirmAccessTestSupport.real(new MutableClock(LocalDateTime.of(2026, 12, 10, 10, 0)), blocked, active));

        scheduler.syncAll();

        verify(syncService, never()).syncFirm(eq(blockedFirm.getId()), any());
        verify(syncService).syncFirm(eq(activeFirm.getId()), any());

        blocked.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        scheduler.syncAll();
        verify(syncService).syncFirm(eq(blockedFirm.getId()), any());
    }

    private static FirmCertificate certificate(AccountingFirm firm) {
        FirmCertificate certificate = new FirmCertificate();
        certificate.setAccountingFirm(firm);
        certificate.setValidUntil(LocalDateTime.of(2030, 1, 1, 0, 0));
        return certificate;
    }
}
