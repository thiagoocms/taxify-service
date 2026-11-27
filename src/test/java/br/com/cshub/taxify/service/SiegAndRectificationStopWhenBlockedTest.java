package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CA1-12 / CT1-20 / CT1-41 (F1-RN-10) para o SIEG e a retificação automática, com o FirmAccessService de verdade e o
 * relógio de Recife. Firm A: teste acabou em 09/12 (bloqueado em 10/12); firm B: ACTIVE. A prova da retomada é a
 * extensão do teste de A até 20/12: a rodada seguinte já processa a empresa dele, sem passo manual.
 */
class SiegAndRectificationStopWhenBlockedTest {

    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 12, 10, 10, 0));
    private AccountingFirm firmA;
    private AccountingFirm firmB;
    private FirmSubscription subscriptionA;
    private FirmAccessService access;
    private Company companyA;
    private Company companyB;

    @BeforeEach
    void setUp() {
        firmA = new AccountingFirm(UUID.randomUUID());
        firmB = new AccountingFirm(UUID.randomUUID());
        subscriptionA = new FirmSubscription();
        subscriptionA.setAccountingFirmId(firmA.getId());
        subscriptionA.setStatus(FirmSubscriptionStatus.TRIAL);
        subscriptionA.setTrialEndsOn(LocalDate.of(2026, 12, 9));
        FirmSubscription subscriptionB = new FirmSubscription();
        subscriptionB.setAccountingFirmId(firmB.getId());
        subscriptionB.setStatus(FirmSubscriptionStatus.ACTIVE);
        access = FirmAccessTestSupport.real(clock, subscriptionA, subscriptionB);
        companyA = company(firmA, "11222333000181");
        companyB = company(firmB, "45723174000110");
    }

    private static Company company(AccountingFirm firm, String cnpj) {
        Company company = new Company(UUID.randomUUID());
        company.setAccountingFirm(firm);
        company.setDocument(new Document(cnpj, DocumentTypeEnum.CNPJ));
        return company;
    }

    private static Subscription entitled(PlanServiceEnum... services) {
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(services));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        return subscription;
    }

    private static FiscalXmlSyncControl control(Company company) {
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(company);
        control.setLastCheckedAt(LocalDateTime.now());
        return control;
    }

    @Test
    void siegCapturesOnlyTheCompaniesOfFirmsThatCanRun() {
        CompanyRepository companies = mock(CompanyRepository.class);
        FiscalXmlSyncControlRepository controls = mock(FiscalXmlSyncControlRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        FiscalXmlProvider provider = mock(FiscalXmlProvider.class);
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));
        when(subscriptions.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                any(), any(), any())).thenReturn(Optional.of(entitled(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION)));
        when(companies.findAll()).thenReturn(List.of(companyA, companyB));
        when(controls.findAllCompanyIds()).thenReturn(List.of(companyA.getId(), companyB.getId()));
        when(controls.findAllWithCompany()).thenReturn(List.of(control(companyA), control(companyB)));
        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setBatchSize(10);
        FiscalXmlSyncScheduler scheduler = new FiscalXmlSyncScheduler(companies, controls, subscriptions, Map.of("sieg", provider),
                properties, access);

        scheduler.syncAllCompanies();

        verify(provider, never()).sync(argThat((FiscalXmlSyncRequest request) -> request.companyId().equals(companyA.getId())));
        verify(provider, atLeastOnce()).sync(argThat((FiscalXmlSyncRequest request) -> request.companyId().equals(companyB.getId())));

        // reactivated: the next round already includes A
        subscriptionA.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        scheduler.syncAllCompanies();
        verify(provider, atLeastOnce()).sync(argThat((FiscalXmlSyncRequest request) -> request.companyId().equals(companyA.getId())));
    }

    @Test
    void rectificationCandidatesLeaveTheBlockedFirmOutAndComeBack() {
        ProductFiscalRuleRepository rules = mock(ProductFiscalRuleRepository.class);
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        when(rules.findCompaniesWithRules()).thenReturn(List.of(companyA, companyB));
        when(subscriptions.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                any(), any(), any())).thenReturn(Optional.of(entitled(PlanServiceEnum.FISCAL_XML_IMPORT)));
        FiscalXmlRectificationScheduler scheduler = new FiscalXmlRectificationScheduler(mock(FiscalXmlDocumentRepository.class), rules,
                subscriptions, mock(FiscalXmlRectificationService.class), new br.com.cshub.taxify.integration.fiscalxml.FiscalXmlRectificationProperties(),
                access);

        assertThat(scheduler.candidates()).containsExactly(companyB);

        subscriptionA.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        assertThat(scheduler.candidates()).containsExactlyInAnyOrder(companyA, companyB);
    }
}
