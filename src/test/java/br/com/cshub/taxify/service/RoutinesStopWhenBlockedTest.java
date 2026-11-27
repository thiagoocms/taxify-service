package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.acessorias.AcessoriasProperties;
import br.com.cshub.taxify.integration.nfse.AdnProperties;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.CompanyCertificateRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport;
import br.com.cshub.taxify.service.firmaccess.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CA1-12 / CT1-20 / CT1-41 (F1-RN-10): NFS-e nacional, Acessórias e Conta Azul (sincronização do Plano de honorário) com
 * o FirmAccessService de verdade e o relógio de Recife. O escritório A tem o teste que acabou em 09/12; o B é ACTIVE.
 * Em 10/12 só as empresas do B são processadas; com o teste de A estendido até 20/12, a próxima rodada processa as duas.
 */
class RoutinesStopWhenBlockedTest {

    private static final String CNPJ_A = "11222333000181";
    private static final String CNPJ_B = "45723174000110";

    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 12, 10, 10, 0));
    private AccountingFirm firmA;
    private AccountingFirm firmB;
    private FirmSubscription subscriptionA;
    private FirmAccessService access;

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
    }

    private static Company company(AccountingFirm firm, String cnpj) {
        Company company = new Company(UUID.randomUUID());
        company.setAccountingFirm(firm);
        company.setDocument(new Document(cnpj, DocumentTypeEnum.CNPJ));
        return company;
    }

    @Test
    void nfseAdnLeavesTheBlockedFirmOutAndTakesItBackWhenTheTrialIsExtended() {
        Company companyA = company(firmA, CNPJ_A);
        Company companyB = company(firmB, CNPJ_B);
        CompanyCertificateRepository certificates = mock(CompanyCertificateRepository.class);
        CompanyCertificate certificateA = new CompanyCertificate();
        certificateA.setCompany(companyA);
        CompanyCertificate certificateB = new CompanyCertificate();
        certificateB.setCompany(companyB);
        when(certificates.findAllWithCompany()).thenReturn(List.of(certificateA, certificateB));
        CompanyRepository companies = mock(CompanyRepository.class);
        when(companies.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firmA.getId())).thenReturn(List.of(companyA));
        when(companies.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firmB.getId())).thenReturn(List.of(companyB));
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.NFSE_NATIONAL_INTEGRATION));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        when(subscriptions.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                any(), any(), any())).thenReturn(Optional.of(subscription));
        NfseAdnScheduler scheduler = new NfseAdnScheduler(certificates, companies, subscriptions, mock(NfseAdnService.class),
                new AdnProperties(), access);

        assertThat(scheduler.candidates()).containsExactly(companyB);

        // CT1-41: reactivated (the platform admin extended the trial) - the next round takes the firm back, no manual step
        subscriptionA.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        assertThat(scheduler.candidates()).containsExactlyInAnyOrder(companyA, companyB);
    }

    @Test
    void acessoriasDoesNotStartTheBlockedFirmAndStartsItAfterTheExtension() {
        FirmIntegrationService integrations = mock(FirmIntegrationService.class);
        when(integrations.findConfigured(IntegrationType.ACESSORIAS))
                .thenReturn(List.of(new FirmIntegration(firmA, IntegrationType.ACESSORIAS), new FirmIntegration(firmB, IntegrationType.ACESSORIAS)));
        AcessoriasImportService importService = mock(AcessoriasImportService.class);
        AcessoriasProperties properties = new AcessoriasProperties();
        properties.setSyncEnabled(true);
        AcessoriasImportScheduler scheduler = new AcessoriasImportScheduler(integrations, importService, properties, access);

        scheduler.importAllFirms();

        verify(importService, never()).start(eq(firmA), eq(true));
        verify(importService).start(firmB, true);

        subscriptionA.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        scheduler.importAllFirms();
        verify(importService).start(firmA, true);
    }

    @Test
    void contaAzulSyncOfTheBlockedFirmStopsAndComesBack() {
        firmA.setBillingProvider(BillingProviderType.CONTA_AZUL);
        firmB.setBillingProvider(BillingProviderType.CONTA_AZUL);
        AccountingFirmRepository firms = mock(AccountingFirmRepository.class);
        when(firms.findAll()).thenReturn(List.of(firmA, firmB));
        BillingSyncService syncService = mock(BillingSyncService.class);
        BillingSyncScheduler scheduler = new BillingSyncScheduler(firms, syncService, true, access);

        scheduler.syncAllFirms();

        verify(syncService, never()).start(eq(firmA), eq(true));
        verify(syncService).start(firmB, true);

        subscriptionA.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        scheduler.syncAllFirms();
        verify(syncService).start(firmA, true);
    }

    /** Suspended by hand and canceled stop too; a firm without a firm_subscription row counts as suspended (F1-RN-07). */
    @Test
    void suspendedCanceledAndWithoutARowAreTreatedTheSame() {
        FirmSubscription suspended = new FirmSubscription();
        suspended.setAccountingFirmId(firmA.getId());
        suspended.setStatus(FirmSubscriptionStatus.SUSPENDED);
        FirmSubscription canceled = new FirmSubscription();
        canceled.setAccountingFirmId(firmB.getId());
        canceled.setStatus(FirmSubscriptionStatus.CANCELED);
        FirmAccessService service = FirmAccessTestSupport.real(clock, suspended, canceled);

        assertThat(service.allowsAutomaticRoutines(firmA.getId())).isFalse();
        assertThat(service.allowsAutomaticRoutines(firmB.getId())).isFalse();
        assertThat(service.allowsAutomaticRoutines(UUID.randomUUID())).isFalse();
        suspended.setStatus(FirmSubscriptionStatus.TRIAL);
        suspended.setTrialEndsOn(LocalDate.of(2026, 12, 20));
        assertThat(service.allowsAutomaticRoutines(firmA.getId())).isTrue();
    }
}
