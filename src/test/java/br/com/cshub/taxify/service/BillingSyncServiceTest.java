package br.com.cshub.taxify.service;

import br.com.cshub.taxify.service.job.SyncProgress;
import br.com.cshub.taxify.service.job.IntegrationJobRunner;
import br.com.cshub.taxify.common.CurrentFirmService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.domain.billing.ExternalLink;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.dto.billing.BillingSyncResultDTO;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.enumerated.SubscriptionStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.billing.BillingProvider;
import br.com.cshub.taxify.integration.billing.BillingProviderRegistry;
import br.com.cshub.taxify.integration.billing.ChargeQuery;
import br.com.cshub.taxify.integration.billing.ExternalCharge;
import br.com.cshub.taxify.integration.billing.ExternalContract;
import br.com.cshub.taxify.integration.billing.ExternalCustomer;
import br.com.cshub.taxify.repository.BillingChargeRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.ExternalLinkRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingSyncServiceTest {

    @Mock private BillingProvider provider;
    @Mock private FirmIntegrationService firmIntegrationService;
    @Mock private CurrentFirmService currentFirmService;
    @Mock private IntegrationJobRunner jobRunner;
    @Mock private CompanyRepository companyRepository;
    @Mock private ExternalLinkRepository linkRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private BillingChargeRepository chargeRepository;
    @Mock private MessageService messageService;

    private BillingSyncService service;
    private AccountingFirm firm;
    private Company linkedCompany;
    private Company unmatchedCompany;
    private Subscription subscription;

    @BeforeEach
    void setUp() {
        when(provider.type()).thenReturn(BillingProviderType.CONTA_AZUL);
        when(provider.isConnected(any())).thenReturn(true);
        service = new BillingSyncService(new BillingProviderRegistry(List.of(provider), messageService), currentFirmService,
                firmIntegrationService, jobRunner, companyRepository, linkRepository, subscriptionRepository, chargeRepository, messageService);

        firm = new AccountingFirm(UUID.randomUUID());
        firm.setBillingProvider(BillingProviderType.CONTA_AZUL);
        linkedCompany = company("Cliente A", "11594485000105");
        unmatchedCompany = company("Cliente B", "38217129000129");
        subscription = new Subscription();
        subscription.setId(UUID.randomUUID());
        subscription.setCompany(linkedCompany);

        when(companyRepository.findByAccountingFirm_Id(firm.getId())).thenReturn(List.of(linkedCompany, unmatchedCompany));
        when(linkRepository.findByCompany_AccountingFirm_IdAndProviderAndDeletedIsFalse(firm.getId(), BillingProviderType.CONTA_AZUL))
                .thenReturn(List.of());
        when(linkRepository.save(any(ExternalLink.class))).thenAnswer(inv -> inv.getArgument(0));
        when(provider.findCustomerByDocument(firm, "11594485000105"))
                .thenReturn(Optional.of(new ExternalCustomer("cust-a", "Cliente A Ltda", "11594485000105")));
        when(provider.findCustomerByDocument(firm, "38217129000129")).thenReturn(Optional.empty());
        when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                eq(linkedCompany.getId()), any(), any())).thenReturn(Optional.of(subscription));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> inv.getArgument(0));
        when(chargeRepository.findFirstByCompany_IdAndSourceAndExternalId(any(), any(), anyString())).thenReturn(Optional.empty());
        when(chargeRepository.save(any(BillingCharge.class))).thenAnswer(inv -> inv.getArgument(0));
        when(firmIntegrationService.getOrCreate(firm.getId(), IntegrationType.CONTA_AZUL))
                .thenReturn(new FirmIntegration(firm, IntegrationType.CONTA_AZUL));
        when(messageService.getMessage(anyString(), any(String[].class))).thenAnswer(inv -> inv.getArgument(0));
        when(messageService.getMessage(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void linksByDocumentCopiesTheActiveContractAndTakesTheFeeFromTheLatestCharge() {
        LocalDate due = LocalDate.now().plusDays(5);
        when(provider.listContracts(firm, "cust-a")).thenReturn(List.of(
                new ExternalContract("ct-1", "17", "cust-a", SubscriptionStatus.ACTIVE, LocalDate.of(2025, 1, 1), null, due, null, null)));
        when(provider.listCharges(eq(firm), any(ChargeQuery.class))).thenReturn(List.of(
                new ExternalCharge("ch-1", "cust-a", "Honorários nov", due, due, new BigDecimal("850.00"),
                        BigDecimal.ZERO, new BigDecimal("850.00"), ChargeStatus.OPEN)));
        BillingCharge latest = new BillingCharge();
        latest.setAmount(new BigDecimal("850.00"));
        when(chargeRepository.findFirstByCompany_IdAndSourceAndStatusNotInAndDueDateLessThanEqualAndDeletedIsFalseOrderByDueDateDesc(
                eq(linkedCompany.getId()), eq(BillingProviderType.CONTA_AZUL), any(), any())).thenReturn(Optional.of(latest));

        BillingSyncResultDTO result = service.syncFirm(firm, false, SyncProgress.NONE);

        assertThat(result.newlyLinked()).isEqualTo(1);
        assertThat(result.unmatched()).containsExactly("Cliente B");
        assertThat(result.subscriptionsUpdated()).isEqualTo(1);
        assertThat(result.chargesSynced()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();

        assertThat(subscription.getSource()).isEqualTo(BillingProviderType.CONTA_AZUL);
        assertThat(subscription.getExternalContractNumber()).isEqualTo("17");
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getNextDueDate()).isEqualTo(due);
        assertThat(subscription.getBillingDay()).isEqualTo(due.getDayOfMonth());
        assertThat(subscription.getFee()).isEqualByComparingTo("850.00");
        assertThat(subscription.getSyncWarning()).isNull();

        ArgumentCaptor<BillingCharge> charge = ArgumentCaptor.forClass(BillingCharge.class);
        verify(chargeRepository).save(charge.capture());
        assertThat(charge.getValue().getCompany()).isSameAs(linkedCompany);
        assertThat(charge.getValue().getSubscription()).isSameAs(subscription);
        assertThat(charge.getValue().getExternalId()).isEqualTo("ch-1");
        verify(firmIntegrationService).markSynced(eq(firm.getId()), eq(IntegrationType.CONTA_AZUL), any(LocalDateTime.class));
    }

    @Test
    void severalActiveContractsLinkTheMostRecentAndWarn() {
        when(provider.listContracts(firm, "cust-a")).thenReturn(List.of(
                new ExternalContract("old", "1", "cust-a", SubscriptionStatus.ACTIVE, LocalDate.of(2023, 1, 1), null, null, null, null),
                new ExternalContract("new", "2", "cust-a", SubscriptionStatus.ACTIVE, LocalDate.of(2025, 6, 1), null, null, null, null)));
        when(provider.listCharges(eq(firm), any(ChargeQuery.class))).thenReturn(List.of());

        BillingSyncResultDTO result = service.syncFirm(firm, false, SyncProgress.NONE);

        assertThat(subscription.getExternalContractId()).isEqualTo("new");
        assertThat(subscription.getSyncWarning()).isEqualTo("billing.sync.warning.multiple.contracts");
        assertThat(result.warnings()).hasSize(1);
    }

    @Test
    void manualFirmHasNothingToSync() {
        firm.setBillingProvider(BillingProviderType.MANUAL);

        assertThatThrownBy(() -> service.syncFirm(firm, false, SyncProgress.NONE)).isInstanceOf(BadRequestException.class);
        verify(provider, never()).listCharges(any(), any());
    }

    @Test
    void openChargePastDueIsOverdue() {
        ExternalCharge late = new ExternalCharge("x", "c", null, null, LocalDate.now().minusDays(3),
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, ChargeStatus.OPEN);
        ExternalCharge paid = new ExternalCharge("y", "c", null, null, LocalDate.now().minusDays(3),
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, ChargeStatus.PAID);

        assertThat(BillingSyncService.effectiveStatus(late)).isEqualTo(ChargeStatus.OVERDUE);
        assertThat(BillingSyncService.effectiveStatus(paid)).isEqualTo(ChargeStatus.PAID);
    }

    private static Company company(String name, String cnpj) {
        Company company = new Company(UUID.randomUUID());
        company.setName(name);
        company.setDocument(new Document(cnpj, DocumentTypeEnum.CNPJ));
        return company;
    }
}
