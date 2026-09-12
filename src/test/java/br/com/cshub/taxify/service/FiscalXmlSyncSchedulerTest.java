package br.com.cshub.taxify.service;

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
                Map.of("sieg", provider), properties);

        companyA = companyWithId("00000000-0000-0000-0000-00000000000a");
        companyB = companyWithId("00000000-0000-0000-0000-00000000000b");
        companyC = companyWithId("00000000-0000-0000-0000-00000000000c");
        companyD = companyWithId("00000000-0000-0000-0000-00000000000d");
        companyE = companyWithId("00000000-0000-0000-0000-00000000000e");

        // Every company is entitled by default (active subscription on a plan with
        // FISCAL_XML_IMPORT), so existing tests keep exercising batching/skip logic
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
        verify(provider).sync(captor.capture());
        assertThat(captor.getValue().alreadyImportedCount()).isEqualTo(150);
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
        verify(provider).sync(captor.capture());
        assertThat(captor.getValue().dataEmissaoInicio()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(captor.getValue().dataEmissaoFim()).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    @Test
    void skipsTheProviderCallWhenAlreadyFullyImportedForToday() {
        properties.setReferenceDate(LocalDate.of(2026, 8, 15));
        FiscalXmlSyncControl caughtUp = controlFor(companyA, 200);
        caughtUp.setTotalSieg(200);
        caughtUp.setUltimaVerificacaoEm(LocalDate.of(2026, 8, 15).atTime(9, 0));
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(caughtUp));

        scheduler.syncAllCompanies();

        verifyNoInteractions(provider);
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

        verify(provider).sync(any());
    }

    @Test
    void neverSkipsWhenTotalSiegIsZeroRegardlessOfLastCheckDate() {
        properties.setReferenceDate(LocalDate.of(2026, 8, 15));
        FiscalXmlSyncControl neverPending = controlFor(companyA, 0);
        neverPending.setTotalSieg(0);
        neverPending.setUltimaVerificacaoEm(LocalDate.of(2026, 8, 15).atTime(9, 0));
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(neverPending));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider).sync(any());
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
        verify(provider).sync(captor.capture());
        assertThat(captor.getValue().knownPending()).isTrue();
    }

    @Test
    void doesNotMarkKnownPendingWhenTotalSiegHasNeverBeenChecked() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0)));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        ArgumentCaptor<FiscalXmlSyncRequest> captor = ArgumentCaptor.forClass(FiscalXmlSyncRequest.class);
        verify(provider).sync(captor.capture());
        assertThat(captor.getValue().knownPending()).isFalse();
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
    void syncsACompanyWithAnActiveSubscriptionEntitledToFiscalXmlImport() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(companyA.getId()));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(companyA, 0)));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider).sync(any());
    }

    @Test
    void onlySyncsABatchOfCompaniesPerTick() {
        when(companyRepository.findAll()).thenReturn(List.of(companyA, companyB, companyC, companyD, companyE));
        when(syncControlRepository.findAllCompanyIds()).thenReturn(allIds());
        when(syncControlRepository.findAllWithCompany()).thenReturn(allControls());
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler.syncAllCompanies();

        verify(provider, times(2)).sync(any());
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
        plan.setServices(Set.of(PlanServiceEnum.FISCAL_XML_IMPORT));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        return subscription;
    }

    private FiscalXmlSyncRequest argThatCompanyId(UUID companyId) {
        return org.mockito.ArgumentMatchers.argThat(request -> request != null && request.companyId().equals(companyId));
    }

    private Company companyWithId(String id) {
        Company company = new Company(UUID.fromString(id));
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        return company;
    }
}
