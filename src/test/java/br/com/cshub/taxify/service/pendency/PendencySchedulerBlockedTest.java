package br.com.cshub.taxify.service.pendency;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CA1-12 / CT1-20 / CT1-41 (F1-RN-10, decisão 15): as pendências do escritório bloqueado não são regeradas; voltam ao reativar. */
class PendencySchedulerBlockedTest {

    @Test
    void theBlockedFirmIsNotRefreshedAndIsAgainAfterTheExtension() {
        AccountingFirm firmA = new AccountingFirm(UUID.randomUUID());
        AccountingFirm firmB = new AccountingFirm(UUID.randomUUID());
        Company companyA = company(firmA);
        Company companyB = company(firmB);
        FirmSubscription blocked = new FirmSubscription();
        blocked.setAccountingFirmId(firmA.getId());
        blocked.setStatus(FirmSubscriptionStatus.TRIAL);
        blocked.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        FirmSubscription active = new FirmSubscription();
        active.setAccountingFirmId(firmB.getId());
        active.setStatus(FirmSubscriptionStatus.ACTIVE);
        CompanyRepository companies = mock(CompanyRepository.class);
        when(companies.findAll()).thenReturn(List.of(companyA, companyB));
        PendencyService service = mock(PendencyService.class);
        PendencyScheduler scheduler = new PendencyScheduler(companies, service, mock(CaptureRunRepository.class), true,
                FirmAccessTestSupport.real(new MutableClock(LocalDateTime.of(2026, 12, 10, 10, 0)), blocked, active));

        scheduler.refreshAll();

        verify(service, never()).refresh(companyA);
        verify(service).refresh(companyB);

        blocked.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        scheduler.refreshAll();
        verify(service).refresh(companyA);
    }

    private static Company company(AccountingFirm firm) {
        Company company = new Company(UUID.randomUUID());
        company.setAccountingFirm(firm);
        return company;
    }
}
