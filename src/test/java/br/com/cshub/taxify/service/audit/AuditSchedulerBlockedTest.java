package br.com.cshub.taxify.service.audit;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CA1-12 / CT1-20 / CT1-41 (F1-RN-10, decisão 15): a auditoria automática deixa de fora a empresa do escritório bloqueado. */
class AuditSchedulerBlockedTest {

    @Test
    void theBlockedFirmIsNotAuditedAndIsAgainAfterTheExtension() {
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
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(PlanServiceEnum.INVOICE_AUDIT));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        when(subscriptions.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                any(), any(), any())).thenReturn(Optional.of(subscription));
        AuditScheduler scheduler = new AuditScheduler(companies, subscriptions, mock(AuditService.class), true,
                FirmAccessTestSupport.real(new MutableClock(LocalDateTime.of(2026, 12, 10, 10, 0)), blocked, active));

        assertThat(scheduler.candidates()).containsExactly(companyB);

        blocked.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        assertThat(scheduler.candidates()).containsExactlyInAnyOrder(companyA, companyB);
    }

    private static Company company(AccountingFirm firm) {
        Company company = new Company(UUID.randomUUID());
        company.setAccountingFirm(firm);
        return company;
    }
}
