package br.com.cshub.taxify.service.certidao;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CA1-12 / CT1-20 / CT1-41 (F1-RN-10, decisão 15): certidões não são emitidas para o escritório bloqueado; voltam ao estender o teste. */
class CertidaoSchedulerBlockedTest {

    @Test
    void noCertidaoIsRequestedForABlockedFirmAndItComesBackWhenReactivated() {
        AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
        Company company = new Company(UUID.randomUUID());
        company.setAccountingFirm(firm);
        FirmSubscription subscription = new FirmSubscription();
        subscription.setAccountingFirmId(firm.getId());
        subscription.setStatus(FirmSubscriptionStatus.TRIAL);
        subscription.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        CertidaoService service = mock(CertidaoService.class);
        when(service.dueCompanies()).thenReturn(List.of(company));
        when(service.issue(company)).thenReturn(new CertidaoService.Attempt(true, false, "ok"));
        CertidaoScheduler scheduler = new CertidaoScheduler(service, true,
                FirmAccessTestSupport.real(new MutableClock(LocalDateTime.of(2026, 12, 10, 10, 0)), subscription));

        scheduler.runAll();
        verify(service, never()).issue(any());
        verify(service, never()).recordSkipped(any(), any());

        subscription.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        scheduler.runAll();
        verify(service, times(1)).issue(company);
    }
}
