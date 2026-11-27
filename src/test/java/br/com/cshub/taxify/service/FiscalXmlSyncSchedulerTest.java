package br.com.cshub.taxify.service;

import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiscalXmlSyncSchedulerTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private FiscalXmlSyncControlRepository syncControlRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private FiscalXmlProvider provider;

    private FiscalXmlSyncProperties properties;
    private FiscalXmlSyncScheduler scheduler;

    private Company companyA;
    private Company companyB;
    private Company companyC;
    private Company companyD;
    private Company companyE;

    @BeforeEach
    void setUp() {
        properties = new FiscalXmlSyncProperties();
        properties.setBatchSize(2);
        scheduler = new FiscalXmlSyncScheduler(companyRepository, syncControlRepository, subscriptionRepository,
                Map.of("sieg", provider), properties, br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport.allowAll());

        companyA = companyWithId("00000000-0000-0000-0000-00000000000a");
        companyB = companyWithId("00000000-0000-0000-0000-00000000000b");
        companyC = companyWithId("00000000-0000-0000-0000-00000000000c");
        companyD = companyWithId("00000000-0000-0000-0000-00000000000d");
        companyE = companyWithId("00000000-0000-0000-0000-00000000000e");

        // Every company is entitled by default (active subscription on a plan with
        // FISCAL_XML_IMPORT + SIEG_INTEGRATION), so existing tests keep exercising batching/skip logic
        // without also having to set up a subscription each time. Tests about the
        // entitlement gate itself override this per company.
        lenient().when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        any(), any(), any()))
                .thenReturn(Optional.of(entitledSubscription()));
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setEnabled(false);

        scheduler.syncAllCompanies();

        verifyNoInteractions(companyRepository, syncControlRepository, provider);
    }

    @Test
    void doesNothingWhenTheConfiguredProviderIsNotRegistered() {
        properties.setProvider("unknown");

        scheduler.syncAllCompanies();

        verifyNoInteractions(companyRepository, syncControlRepository, provider);
    }

    @Test
    void seedsAZeroedControlRowForACompanyThatHasNeverBeenSynced() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of());
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of());

        scheduler.syncAllCompanies();

        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        FiscalXmlSyncControl saved = captor.getValue();
        assertThat(saved.getCompany().getId()).isEqualTo(companyA.getId());
        assertThat(saved.getTotalSieg()).isZero();
        assertThat(saved.getTotalImportado()).isZero();
        verifyNoInteractions(provider);
    }

    /** QA-04 / F1-RN-10: no control row for a company of a blocked firm; the first tick after the reactivation creates it. */
    @Test
    void aBlockedFirmGetsItsControlRowOnlyAfterTheReactivation() {
        br.com.cshub.taxify.domain.accountingfirm.AccountingFirm firm =
                new br.com.cshub.taxify.domain.accountingfirm.AccountingFirm(UUID.randomUUID());
        companyA.setAccountingFirm(firm);
        java.util.Set<UUID> blocked = new java.util.HashSet<>(java.util.Set.of(firm.getId()));
        FiscalXmlSyncScheduler gated = new FiscalXmlSyncScheduler(companyRepository, syncControlRepository, subscriptionRepository,
                Map.of("sieg", provider), properties, br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport.blocking(blocked));
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of());
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of());

        gated.syncAllCompanies();
        verify(syncControlRepository, never()).save(any());

        blocked.clear();
        gated.syncAllCompanies();
        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        assertThat(captor.getValue().getCompany().getId()).isEqualTo(companyA.getId());
        verifyNoInteractions(provider);
    }

    @Test
    void doesNotReseedACompanyThatAlreadyHasAControlRow() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0)));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(syncControlRepository, never()).save(any());
    }

    @Test
    void usesTotalImportadoFromTheControlRowAsTheAlreadyImportedCount() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 150)));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        ArgumentCaptor<FiscalXmlSyncRequest> captor = ArgumentCaptor.forClass(FiscalXmlSyncRequest.class);
        verify(provider, atLeastOnce()).sync(captor.capture());
        assertThat(received(captor).alreadyImportedCount()).isEqualTo(150);
    }

    @Test
    void usesTheConfiguredReferenceDateInsteadOfTodayWhenSet() {
        properties.setReferenceDate(LocalDate.of(2026, 8, 15));
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0)));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        ArgumentCaptor<FiscalXmlSyncRequest> captor = ArgumentCaptor.forClass(FiscalXmlSyncRequest.class);
        verify(provider, atLeastOnce()).sync(captor.capture());
        assertThat(received(captor).dataEmissaoInicio()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(received(captor).dataEmissaoFim()).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    void issuedSideUsesItsOwnCountersAndIsSyncedEvenWhenReceivedIsCaughtUp() {
        properties.setReferenceDate(LocalDate.of(2026, 8, 15));
        FiscalXmlSyncControl control = controlFor(companyA, 200);
        control.setTotalSieg(200);
        control.setUltimaVerificacaoEm(LocalDate.of(2026, 8, 15).atTime(9, 0));
        control.setIssuedTotalSieg(30);
        control.setIssuedTotalImported(12);
        control.setIssuedCheckedAt(LocalDate.of(2026, 8, 14).atTime(9, 0));
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(control));

        scheduler.syncAllCompanies();

        ArgumentCaptor<FiscalXmlSyncRequest> captor = ArgumentCaptor.forClass(FiscalXmlSyncRequest.class);
        verify(provider).sync(captor.capture());
        assertThat(captor.getValue().side()).isEqualTo(FiscalXmlSide.ISSUED);
        assertThat(captor.getValue().alreadyImportedCount()).isEqualTo(12);
        assertThat(captor.getValue().knownPending()).isTrue();
    }

    @Test
    void sweepsRecentEventsOncePerDayPerSide() {
        properties.setReferenceDate(LocalDate.of(2026, 8, 15));
        FiscalXmlSyncControl control = controlFor(companyA, 0);
        control.setId(UUID.randomUUID());
        control.setReceivedEventsCheckedOn(LocalDate.of(2026, 8, 14));
        control.setIssuedEventsCheckedOn(LocalDate.of(2026, 8, 15));
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(control));
        when(syncControlRepository.reserve(eq(control.getId()), any(), any())).thenReturn(1); // a persisted row: reserved
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider).refreshEvents(companyA.getId(), FiscalXmlSide.RECEIVED, LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 15));
        verify(provider, never()).refreshEvents(any(), org.mockito.ArgumentMatchers.eq(FiscalXmlSide.ISSUED), any(), any());
        verify(syncControlRepository).markReceivedEventsChecked(control.getId(), LocalDate.of(2026, 8, 15));
    }

    @Test
    void skipsTheProviderCallWhenAlreadyFullyImportedForToday() {
        properties.setReferenceDate(LocalDate.of(2026, 8, 15));
        FiscalXmlSyncControl caughtUp = controlFor(companyA, 200);
        caughtUp.setTotalSieg(200);
        caughtUp.setUltimaVerificacaoEm(LocalDate.of(2026, 8, 15).atTime(9, 0));
        caughtUp.setIssuedTotalSieg(40);
        caughtUp.setIssuedTotalImported(40);
        caughtUp.setIssuedCheckedAt(LocalDate.of(2026, 8, 15).atTime(9, 0));
        caughtUp.setReceivedEventsCheckedOn(LocalDate.of(2026, 8, 15));
        caughtUp.setIssuedEventsCheckedOn(LocalDate.of(2026, 8, 15));
        caughtUp.setServiceInvoicesCheckedOn(LocalDate.of(2026, 8, 15));
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(caughtUp));

        scheduler.syncAllCompanies();

        verifyNoInteractions(provider);
    }

    @Test
    void serviceInvoicesOnceADayThreeMonthsBackTheFirstTime() {
        LocalDate today = LocalDate.of(2026, 8, 15);
        FiscalXmlSyncControl never = controlFor(companyA, 0);

        scheduler.syncServiceInvoicesOncePerDay(provider, never, today);

        verify(provider).syncServiceInvoices(companyA.getId(), LocalDate.of(2026, 5, 1), today);
        verify(syncControlRepository).markServiceInvoicesChecked(never.getId(), today);

        // A1: afterwards only the current month, and only when the provider's count shows something missing
        FiscalXmlSyncControl checkedYesterday = controlFor(companyA, 0);
        checkedYesterday.setServiceInvoicesCheckedOn(today.minusDays(1));
        scheduler.syncServiceInvoicesOncePerDay(provider, checkedYesterday, today);
        verify(provider).syncServiceInvoicesIfMissing(companyA.getId(), LocalDate.of(2026, 8, 1), today, null, null);

        FiscalXmlSyncControl checkedToday = controlFor(companyA, 0);
        checkedToday.setServiceInvoicesCheckedOn(today);
        scheduler.syncServiceInvoicesOncePerDay(provider, checkedToday, today);
        verify(provider, times(1)).syncServiceInvoices(any(), any(), any());
        verify(provider, times(1)).syncServiceInvoicesIfMissing(any(), any(), any(), any(), any());
    }

    @Test
    void checksAgainOnceTheCalendarDayChangesEvenIfAlreadyCaughtUp() {
        properties.setReferenceDate(LocalDate.of(2026, 8, 15));
        FiscalXmlSyncControl caughtUpYesterday = controlFor(companyA, 200);
        caughtUpYesterday.setTotalSieg(200);
        caughtUpYesterday.setUltimaVerificacaoEm(LocalDate.of(2026, 8, 14).atTime(23, 0));
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(caughtUpYesterday));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider).sync(receivedSide());
    }

    /** A3: a zero total is only "up to date" for a side really checked (the window written) - a new row is counted. */
    @Test
    void aZeroTotalNeverVerifiedIsCountedEvenWhenItsTimeIsToday() {
        properties.setReferenceDate(LocalDate.of(2026, 8, 15));
        FiscalXmlSyncControl neverPending = controlFor(companyA, 0);
        neverPending.setTotalSieg(0);
        neverPending.setUltimaVerificacaoEm(LocalDate.of(2026, 8, 15).atTime(9, 0));
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(neverPending));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider).sync(receivedSide());
    }

    @Test
    void marksTheRequestAsKnownPendingWhenTotalImportadoIsAlreadyBelowTotalSieg() {
        FiscalXmlSyncControl pending = controlFor(companyA, 100);
        pending.setTotalSieg(200);
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(pending));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        ArgumentCaptor<FiscalXmlSyncRequest> captor = ArgumentCaptor.forClass(FiscalXmlSyncRequest.class);
        verify(provider, atLeastOnce()).sync(captor.capture());
        assertThat(received(captor).knownPending()).isTrue();
    }

    @Test
    void doesNotMarkKnownPendingWhenTotalSiegHasNeverBeenChecked() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0)));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        ArgumentCaptor<FiscalXmlSyncRequest> captor = ArgumentCaptor.forClass(FiscalXmlSyncRequest.class);
        verify(provider, atLeastOnce()).sync(captor.capture());
        assertThat(received(captor).knownPending()).isFalse();
    }

    @Test
    void excludesACompanyWithNoActiveSubscriptionFromTheBatch() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0)));
        when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        eq(companyA.getId()), any(), any()))
                .thenReturn(Optional.empty());

        scheduler.syncAllCompanies();

        verifyNoInteractions(provider);
    }

    @Test
    void excludesACompanyWhosePlanDoesNotIncludeFiscalXmlImport() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0)));
        Plan planWithoutTheService = new Plan(UUID.randomUUID());
        planWithoutTheService.setServices(Set.of(PlanServiceEnum.REPORTS));
        Subscription subscriptionOnWrongPlan = new Subscription(UUID.randomUUID());
        subscriptionOnWrongPlan.setPlan(planWithoutTheService);
        when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        eq(companyA.getId()), any(), any()))
                .thenReturn(Optional.of(subscriptionOnWrongPlan));

        scheduler.syncAllCompanies();

        verifyNoInteractions(provider);
    }

    @Test
    void importWithoutSiegAsSourceIsNotSyncedFromSieg() {
        for (Set<PlanServiceEnum> services : List.of(
                Set.of(PlanServiceEnum.FISCAL_XML_IMPORT),
                Set.of(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION),
                Set.of(PlanServiceEnum.SIEG_INTEGRATION))) {
            when(companyRepository.findAll()).thenReturn(List.of(companyA));
            when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
            when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0)));
            Plan plan = new Plan(UUID.randomUUID());
            plan.setServices(services);
            Subscription subscription = new Subscription(UUID.randomUUID());
            subscription.setPlan(plan);
            when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                            eq(companyA.getId()), any(), any()))
                    .thenReturn(Optional.of(subscription));

            scheduler.syncAllCompanies();
        }

        verifyNoInteractions(provider);
    }

    @Test
    void syncsACompanyWithAnActiveSubscriptionEntitledToFiscalXmlImport() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0)));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider).sync(receivedSide());
    }

    @Test
    void onlySyncsABatchOfCompaniesPerTick() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA, companyB, companyC, companyD, companyE));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(allIds());
        when(syncControlRepository.findAllWithCompany()).thenReturn(allControls());
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider, times(2)).sync(receivedSide());
        verify(provider).sync(argThatCompanyId(companyA.getId()));
        verify(provider).sync(argThatCompanyId(companyB.getId()));
    }

    @Test
    void rotatesThroughAllCompaniesAcrossSuccessiveTicksAndWrapsAround() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA, companyB, companyC, companyD, companyE));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(allIds());
        when(syncControlRepository.findAllWithCompany()).thenReturn(allControls());
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies(); // A, B
        scheduler.syncAllCompanies(); // C, D
        scheduler.syncAllCompanies(); // E, wraps to A

        verify(provider, times(2)).sync(argThatCompanyId(companyA.getId()));
        verify(provider, times(1)).sync(argThatCompanyId(companyB.getId()));
        verify(provider, times(1)).sync(argThatCompanyId(companyC.getId()));
        verify(provider, times(1)).sync(argThatCompanyId(companyD.getId()));
        verify(provider, times(1)).sync(argThatCompanyId(companyE.getId()));
    }

    /** A restart (a new scheduler over the same rows) goes on with the queue - no in-memory cursor back to zero. */
    @Test
    void aRestartDoesNotSendTheQueueBackToTheStart() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA, companyB, companyC, companyD, companyE));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(allIds());
        List<FiscalXmlSyncControl> rows = allControls();
        when(syncControlRepository.findAllWithCompany()).thenReturn(rows);
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies(); // A, B
        FiscalXmlSyncScheduler restarted = new FiscalXmlSyncScheduler(companyRepository, syncControlRepository, subscriptionRepository,
                Map.of("sieg", provider), properties, br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport.allowAll());
        restarted.syncAllCompanies(); // C, D - not A, B again

        verify(provider, times(1)).sync(argThatCompanyId(companyA.getId()));
        verify(provider, times(1)).sync(argThatCompanyId(companyC.getId()));
        verify(provider, times(1)).sync(argThatCompanyId(companyD.getId()));
        verify(provider, never()).sync(argThatCompanyId(companyE.getId()));
    }

    /** The attempt is persisted before the sync, so it survives the restart (the row's last_attempt_at). */
    @Test
    void everyAttemptIsPersistedBeforeTheSync() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        FiscalXmlSyncControl row = controlFor(companyA, 0);
        row.setId(UUID.randomUUID());
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(row));
        when(syncControlRepository.reserve(eq(row.getId()), any(), any())).thenReturn(1);
        when(provider.sync(any())).thenThrow(new RuntimeException("SIEG fora"));

        scheduler.syncAllCompanies();

        verify(syncControlRepository).reserve(eq(row.getId()), org.mockito.ArgumentMatchers.isNull(), any(LocalDateTime.class));
        assertThat(row.getLastAttemptAt()).isNotNull();
    }

    /** A company that always fails goes to the end of the queue like any other - it never holds the rest back. */
    @Test
    void aCompanyThatAlwaysFailsDoesNotBlockTheOthers() {
        properties.setBatchSize(1);
        when(companyRepository.findAll()).thenReturn(List.of(companyA, companyB, companyC));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId(), companyB.getId(), companyC.getId()));
        when(syncControlRepository.findAllWithCompany())
                .thenReturn(List.of(controlFor(companyA, 0), controlFor(companyB, 0), controlFor(companyC, 0)));
        lenient().when(provider.sync(argThatCompanyId(companyA.getId()))).thenThrow(new RuntimeException("erro permanente"));
        lenient().when(provider.sync(argThatCompanyId(companyB.getId()))).thenReturn(new FiscalXmlSyncResult(0, 0, 0));
        lenient().when(provider.sync(argThatCompanyId(companyC.getId()))).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();
        scheduler.syncAllCompanies();
        scheduler.syncAllCompanies();

        verify(provider).sync(argThatCompanyId(companyA.getId()));
        verify(provider).sync(argThatCompanyId(companyB.getId()));
        verify(provider).sync(argThatCompanyId(companyC.getId()));
    }

    /** The bug seen with 306 companies: with a batch of 2, every one of them is visited in 153 ticks. */
    @Test
    void threeHundredSixCompaniesWithABatchOfTwoAreAllVisitedIn153Ticks() {
        properties.setBatchSize(2);
        List<Company> companies = new ArrayList<>();
        List<FiscalXmlSyncControl> rows = new ArrayList<>();
        for (int i = 0; i < 306; i++) {
            Company company = new Company(UUID.randomUUID());
            company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
            companies.add(company);
            FiscalXmlSyncControl row = controlFor(company, 0);
            row.setId(UUID.randomUUID());
            rows.add(row);
        }
        java.util.Collections.shuffle(rows, new java.util.Random(7)); // the database returns them in no particular order
        when(companyRepository.findAll()).thenReturn(companies);
        when(syncControlRepository.findAllCompanyIds()).thenReturn(companies.stream().map(Company::getId).toList());
        when(syncControlRepository.findAllWithCompany()).thenAnswer(invocation -> {
            List<FiscalXmlSyncControl> copy = new ArrayList<>(rows);
            java.util.Collections.shuffle(copy);
            return copy;
        });
        when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                any(), any(), any())).thenReturn(Optional.of(entitledSubscription()));
        when(syncControlRepository.reserve(any(), any(), any())).thenReturn(1);
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        when(provider.sync(any())).thenAnswer(invocation -> {
            visited.add(invocation.<br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest>getArgument(0).companyId());
            return new FiscalXmlSyncResult(0, 0, 0);
        });

        for (int tick = 0; tick < 153; tick++) {
            scheduler.syncAllCompanies();
        }

        assertThat(visited).hasSize(306);
    }

    /**
     * Two instances over the same table: the second reads the queue while the first is still choosing (both see A, B
     * free). The conditional update lets only one of them have each company - the batches are disjoint.
     */
    @Test
    void twoInstancesNeverTakeTheSameCompany() {
        properties.setBatchSize(2);
        List<FiscalXmlSyncControl> table = new ArrayList<>();
        for (Company company : List.of(companyA, companyB, companyC, companyD, companyE)) {
            FiscalXmlSyncControl row = controlFor(company, 0);
            row.setId(UUID.fromString(company.getId().toString().replace("00000000-0000-0000-0000-0000000000", "10000000-0000-0000-0000-0000000000")));
            table.add(row);
        }
        when(companyRepository.findAll()).thenReturn(List.of(companyA, companyB, companyC, companyD, companyE));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(allIds());
        // the "database": each read is a snapshot; reserve is the conditional update on the shared rows
        when(syncControlRepository.reserve(any(), any(), any())).thenAnswer(invocation -> {
            synchronized (table) {
                FiscalXmlSyncControl row = table.stream().filter(r -> r.getId().equals(invocation.getArgument(0))).findFirst().orElseThrow();
                if (!java.util.Objects.equals(row.getLastAttemptAt(), invocation.getArgument(1))) {
                    return 0;
                }
                row.setLastAttemptAt(invocation.getArgument(2));
                return 1;
            }
        });
        FiscalXmlSyncScheduler second = new FiscalXmlSyncScheduler(companyRepository, syncControlRepository, subscriptionRepository,
                Map.of("sieg", provider), properties, br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport.allowAll());
        java.util.concurrent.atomic.AtomicBoolean interleaved = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(syncControlRepository.findAllWithCompany()).thenAnswer(invocation -> {
            List<FiscalXmlSyncControl> snapshot = table.stream().map(this::copyOf).toList();
            if (interleaved.compareAndSet(false, true)) {
                second.syncAllCompanies(); // the other instance's tick runs between this read and the reservations
            }
            return snapshot;
        });
        java.util.List<UUID> synced = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(provider.sync(any())).thenAnswer(invocation -> {
            synced.add(invocation.<br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest>getArgument(0).companyId());
            return new FiscalXmlSyncResult(0, 0, 0);
        });

        scheduler.syncAllCompanies();

        // received + issued per company: 2 calls each; 4 different companies, none twice
        assertThat(synced.stream().distinct().count()).isEqualTo(4);
        assertThat(synced).hasSize(8);
        assertThat(synced).doesNotContain(companyE.getId());
    }

    private FiscalXmlSyncControl copyOf(FiscalXmlSyncControl row) {
        FiscalXmlSyncControl copy = new FiscalXmlSyncControl();
        copy.setId(row.getId());
        copy.setCompany(row.getCompany());
        copy.setTotalImportado(row.getTotalImportado());
        copy.setUltimaVerificacaoEm(row.getUltimaVerificacaoEm());
        copy.setIssuedCheckedAt(row.getIssuedCheckedAt());
        copy.setLastAttemptAt(row.getLastAttemptAt());
        return copy;
    }

    /**
     * QA-05: another instance created company A's row first (Duplicate entry on uk_fiscal_xml_sync_control_company) -
     * that row is just skipped, B's row is still created, and the tick goes on. The creation time is Recife's clock.
     */
    @Test
    void aControlRowCreatedByAnotherInstanceDoesNotStopTheTick() {
        java.time.Clock fixed = java.time.Clock.fixed(java.time.Instant.parse("2026-12-01T12:00:00Z"), java.time.ZoneId.of("America/Recife"));
        FiscalXmlSyncScheduler clocked = new FiscalXmlSyncScheduler(companyRepository, syncControlRepository, subscriptionRepository,
                Map.of("sieg", provider), properties, br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport.allowAll(), null, fixed);
        when(companyRepository.findAll()).thenReturn(List.of(companyA, companyB));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of());
        when(syncControlRepository.save(org.mockito.ArgumentMatchers.argThat(row -> row != null && row.getCompany() == companyA)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup",
                        new java.sql.SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062)));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of());

        clocked.syncAllCompanies();

        ArgumentCaptor<FiscalXmlSyncControl> saved = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(row -> row.getCompany().getId()).containsExactly(companyA.getId(), companyB.getId());
        assertThat(saved.getAllValues().getLast().getUltimaVerificacaoEm()).isEqualTo(LocalDateTime.of(2026, 12, 1, 9, 0));
        // the tick went on past the seeding
        verify(syncControlRepository).findAllWithCompany();
    }

    /** last_attempt_at comes from the injected Recife clock, at datetime(6) precision. */
    @Test
    void theAttemptTimeComesFromTheRecifeClock() {
        java.time.Clock fixed = java.time.Clock.fixed(java.time.Instant.parse("2026-12-01T12:00:00.123456789Z"),
                java.time.ZoneId.of("America/Recife"));
        FiscalXmlSyncScheduler clocked = new FiscalXmlSyncScheduler(companyRepository, syncControlRepository, subscriptionRepository,
                Map.of("sieg", provider), properties, br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport.allowAll(), null, fixed);
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        FiscalXmlSyncControl row = controlFor(companyA, 0);
        row.setId(UUID.randomUUID());
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(row));
        when(syncControlRepository.reserve(any(), any(), any())).thenReturn(1);
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        clocked.syncAllCompanies();

        LocalDateTime recifeNineAm = LocalDateTime.of(2026, 12, 1, 9, 0, 0, 123456000);
        verify(syncControlRepository).reserve(row.getId(), null, recifeNineAm);
        assertThat(row.getLastAttemptAt()).isEqualTo(recifeNineAm);
    }

    /** A brand-new row (ultima_verificacao_em filled on creation, issued side never checked) goes before the checked ones. */
    @Test
    void aNewCompanyGoesBeforeTheCheckedOnes() {
        properties.setBatchSize(1);
        FiscalXmlSyncControl checked = controlFor(companyA, 0);
        checked.setIssuedCheckedAt(LocalDateTime.now().minusDays(3));
        checked.setLastAttemptAt(LocalDateTime.now().minusDays(3));
        FiscalXmlSyncControl brandNew = controlFor(companyB, 0); // only the creation's ultimaVerificacaoEm
        when(companyRepository.findAll()).thenReturn(List.of(companyA, companyB));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId(), companyB.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(checked, brandNew));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider, never()).sync(argThatCompanyId(companyA.getId()));
        verify(provider).sync(argThatCompanyId(companyB.getId()));
        assertThat(brandNew.getVerifiedAt()).isNull();
    }

    /** Among rows never attempted (the ones that existed before V67), the oldest real verification goes first. */
    @Test
    void amongNeverAttemptedTheOldestVerificationGoesFirst() {
        FiscalXmlSyncControl recent = controlFor(companyA, 0);
        recent.setIssuedCheckedAt(LocalDateTime.now().minusHours(1));
        FiscalXmlSyncControl old = controlFor(companyB, 0);
        old.setIssuedCheckedAt(LocalDateTime.now().minusDays(5));
        FiscalXmlSyncControl never = controlFor(companyC, 0);

        List<FiscalXmlSyncControl> ordered = java.util.stream.Stream.of(recent, old, never)
                .sorted(FiscalXmlSyncScheduler.queueOrder()).toList();

        assertThat(ordered).containsExactly(never, old, recent);
    }

    @Test
    void oneFailingCompanyDoesNotStopTheRestOfTheBatch() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA, companyB));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId(), companyB.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0), controlFor(companyB, 0)));
        lenient().when(provider.sync(argThatCompanyId(companyA.getId()))).thenThrow(new RuntimeException("boom"));
        lenient().when(provider.sync(argThatCompanyId(companyB.getId()))).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider).sync(argThatCompanyId(companyB.getId()));
    }

    private List<UUID> allIds() {
        return List.of(companyA.getId(), companyB.getId(), companyC.getId(), companyD.getId(), companyE.getId());
    }

    private List<FiscalXmlSyncControl> allControls() {
        return List.of(controlFor(companyA, 0), controlFor(companyB, 0), controlFor(companyC, 0),
                controlFor(companyD, 0), controlFor(companyE, 0));
    }

    private FiscalXmlSyncControl controlFor(Company company, int totalImportado) {
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(company);
        control.setTotalImportado(totalImportado);
        // Last checked "now" (same month as LocalDate.now(), the default `today` when a
        // test doesn't override referenceDate) so alreadyImportedCount/knownPending flow
        // through unchanged by default - tests about month-rollover behavior set their
        // own ultimaVerificacaoEm explicitly.
        control.setUltimaVerificacaoEm(LocalDateTime.now());
        return control;
    }

    private Subscription entitledSubscription() {
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        return subscription;
    }

    private FiscalXmlSyncRequest argThatCompanyId(UUID companyId) {
        return org.mockito.ArgumentMatchers.argThat(request -> request != null && request.companyId().equals(companyId)
                && request.side() == FiscalXmlSide.RECEIVED);
    }

    /** These tests are about the received side; the issued side runs alongside with its own counters. */
    private FiscalXmlSyncRequest receivedSide() {
        return org.mockito.ArgumentMatchers.argThat(request -> request != null && request.side() == FiscalXmlSide.RECEIVED);
    }

    private static FiscalXmlSyncRequest received(ArgumentCaptor<FiscalXmlSyncRequest> captor) {
        return captor.getAllValues().stream().filter(request -> request.side() == FiscalXmlSide.RECEIVED).findFirst().orElseThrow();
    }

    private Company companyWithId(String id) {
        Company company = new Company(UUID.fromString(id));
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        return company;
    }
}
