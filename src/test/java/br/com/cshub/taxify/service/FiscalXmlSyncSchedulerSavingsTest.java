package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.enumerated.ServiceInvoiceCountGate;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.integration.fiscalxml.ProviderCallContext;
import br.com.cshub.taxify.integration.fiscalxml.ProviderCallPurpose;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A1 (NFS-e só com diferença na contagem, mês corrente e o anterior nos primeiros dias) e A3 (lado com total 0
 * verificado hoje fica em dia por N horas) no agendador, mais a finalidade de cada chamada (A5).
 */
@ExtendWith(MockitoExtension.class)
class FiscalXmlSyncSchedulerSavingsTest {

    private static final ZoneId RECIFE = ZoneId.of("America/Recife");

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private FiscalXmlSyncControlRepository syncControlRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private FiscalXmlProvider provider;

    private FiscalXmlSyncProperties properties;
    private Company company;

    @BeforeEach
    void setUp() {
        properties = new FiscalXmlSyncProperties();
        properties.setBatchSize(1);
        company = new Company(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        lenient().when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                any(), any(), any())).thenReturn(Optional.of(subscription));
        lenient().when(companyRepository.findAll()).thenReturn(List.of(company));
        lenient().when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(company.getId()));
    }

    private FiscalXmlSyncScheduler scheduler(LocalDateTime recifeNow) {
        Clock clock = Clock.fixed(recifeNow.atZone(RECIFE).toInstant(), RECIFE);
        return new FiscalXmlSyncScheduler(companyRepository, syncControlRepository, subscriptionRepository,
                Map.of("sieg", provider), properties, FirmAccessTestSupport.allowAll(), null, clock);
    }

    /** Both sides verified this month with total 0 at {@code checkedAt}; events and NFS-e already done today. */
    private FiscalXmlSyncControl zeroControl(LocalDateTime checkedAt) {
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(company);
        control.setDataEmissaoInicio(checkedAt.toLocalDate().withDayOfMonth(1));
        control.setDataEmissaoFim(checkedAt.toLocalDate());
        control.setTotalSieg(0);
        control.setTotalImportado(0);
        control.setUltimaVerificacaoEm(checkedAt);
        control.setIssuedTotalSieg(0);
        control.setIssuedTotalImported(0);
        control.setIssuedCheckedAt(checkedAt);
        control.setReceivedEventsCheckedOn(checkedAt.toLocalDate());
        control.setIssuedEventsCheckedOn(checkedAt.toLocalDate());
        control.setServiceInvoicesCheckedOn(checkedAt.toLocalDate());
        return control;
    }

    // ------------------------------------------------------------------ A3

    /** A3: a side checked today at 08:00 with total 0 is up to date at 13:59 (6 h by default) - no request. */
    @Test
    void aZeroTotalCheckedTodayIsUpToDateWithinTheRecountWindow() {
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(zeroControl(LocalDateTime.of(2026, 11, 27, 8, 0))));

        scheduler(LocalDateTime.of(2026, 11, 27, 13, 59)).syncAllCompanies();

        verify(provider, never()).sync(any());
    }

    /** A3: after the 6 h the zero side is counted again. */
    @Test
    void aZeroTotalIsCountedAgainAfterTheRecountWindow() {
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(zeroControl(LocalDateTime.of(2026, 11, 27, 8, 0))));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler(LocalDateTime.of(2026, 11, 27, 14, 1)).syncAllCompanies();

        verify(provider).sync(argThat(request -> request != null && request.side() == FiscalXmlSide.RECEIVED));
        verify(provider).sync(argThat(request -> request != null && request.side() == FiscalXmlSide.ISSUED));
    }

    /** A3 off (0 hours): a zero side is counted on every attempt, the old behavior. */
    @Test
    void zeroRecountHoursMeansEveryAttempt() {
        properties.setZeroTotalRecountHours(0);
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(zeroControl(LocalDateTime.of(2026, 11, 27, 8, 0))));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler(LocalDateTime.of(2026, 11, 27, 8, 5)).syncAllCompanies();

        verify(provider, org.mockito.Mockito.times(2)).sync(any());
    }

    /** A3: "today" is a calendar day - a zero side checked yesterday at 23:00 is counted at 00:30. */
    @Test
    void aZeroTotalCheckedYesterdayIsCountedOnTheNewDay() {
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(zeroControl(LocalDateTime.of(2026, 11, 26, 23, 0))));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler(LocalDateTime.of(2026, 11, 27, 0, 30)).syncAllCompanies();

        verify(provider, org.mockito.Mockito.times(2)).sync(any());
    }

    // ------------------------------------------------------------------ A1

    /**
     * A1 in shadow (the default): the last 35 days are downloaded as before, with the free counts both NF-e sides got in
     * this attempt - the issued side's (CnpjEmit) as the issuer count, the received side's (CnpjDest) as the recipient's.
     */
    @Test
    void theShadowGateDownloadsAsBeforeWithThisAttemptsCounts() {
        FiscalXmlSyncControl control = zeroControl(LocalDateTime.of(2026, 11, 26, 9, 0));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(control));
        when(provider.sync(argThat(request -> request != null && request.side() == FiscalXmlSide.RECEIVED)))
                .thenReturn(new FiscalXmlSyncResult(0, 0, 0, false, 4));
        when(provider.sync(argThat(request -> request != null && request.side() == FiscalXmlSide.ISSUED)))
                .thenReturn(new FiscalXmlSyncResult(0, 0, 0, false, 6));

        scheduler(LocalDateTime.of(2026, 11, 27, 9, 0)).syncAllCompanies();

        verify(provider).syncServiceInvoicesShadow(company.getId(), LocalDate.of(2026, 10, 23), LocalDate.of(2026, 11, 27), 6, 4);
        verify(provider, never()).syncServiceInvoicesIfMissing(any(), any(), any(), any(), any());
        verify(syncControlRepository).markServiceInvoicesChecked(control.getId(), LocalDate.of(2026, 11, 27));
    }

    /**
     * A1 on: the 35 days in calendar-month windows, each only when the count shows something missing - the previous month
     * as long as today - 35 falls in it (its own count), the current month with this attempt's counts.
     */
    @Test
    void theGateOnChecksEveryMonthOfTheLast35Days() {
        properties.setServiceInvoiceCountGate(ServiceInvoiceCountGate.ON);
        FiscalXmlSyncControl control = zeroControl(LocalDateTime.of(2026, 12, 19, 9, 0));

        scheduler(LocalDateTime.of(2026, 12, 20, 9, 0)).syncServiceInvoicesOncePerDay(provider, control, LocalDate.of(2026, 12, 20), 1, 2);

        verify(provider).syncServiceInvoicesIfMissing(company.getId(), LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 30), null, null);
        verify(provider).syncServiceInvoicesIfMissing(company.getId(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 20), 1, 2);
        verify(provider, never()).syncServiceInvoices(any(), any(), any());
    }

    /** A1 on, early in a short month: today - 35 falls two months back - three windows. */
    @Test
    void theLookbackCanSpanThreeMonths() {
        assertThat(FiscalXmlSyncScheduler.monthWindows(LocalDate.of(2027, 1, 27), LocalDate.of(2027, 3, 3)))
                .extracting(window -> window[0] + ".." + window[1])
                .containsExactly("2027-01-27..2027-01-31", "2027-02-01..2027-02-28", "2027-03-01..2027-03-03");
    }

    /** A1 off: the old daily download of the last 35 days. */
    @Test
    void withTheGateOffTheLast35DaysAreDownloaded() {
        properties.setServiceInvoiceCountGate(ServiceInvoiceCountGate.OFF);
        FiscalXmlSyncControl control = zeroControl(LocalDateTime.of(2026, 12, 5, 9, 0));

        scheduler(LocalDateTime.of(2026, 12, 6, 9, 0)).syncServiceInvoicesOncePerDay(provider, control, LocalDate.of(2026, 12, 6), 1, 2);

        verify(provider).syncServiceInvoices(company.getId(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 6));
        verify(provider, never()).syncServiceInvoicesIfMissing(any(), any(), any(), any(), any());
        verify(provider, never()).syncServiceInvoicesShadow(any(), any(), any(), any(), any());
    }

    /** B1: the difference a complete pass left this month counts as delivered - 60 of 63 with 3 known missing is up to date. */
    @Test
    void aKnownGapMakesTheSideUpToDate() {
        FiscalXmlSyncControl control = zeroControl(LocalDateTime.of(2026, 11, 27, 8, 0));
        control.setTotalSieg(63);
        control.setTotalImportado(60);
        control.recordKnownGap(false, "2026-11", 3);
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(control));

        scheduler(LocalDateTime.of(2026, 11, 27, 9, 0)).syncAllCompanies();

        verify(provider, never()).sync(any());
    }

    /** B1: a gap of another month does not count - the side is still pending. */
    @Test
    void aKnownGapOfAnotherMonthDoesNotCount() {
        FiscalXmlSyncControl control = zeroControl(LocalDateTime.of(2026, 11, 27, 8, 0));
        control.setTotalSieg(63);
        control.setTotalImportado(60);
        control.recordKnownGap(false, "2026-10", 3);
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(control));
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));

        scheduler(LocalDateTime.of(2026, 11, 27, 9, 0)).syncAllCompanies();

        verify(provider).sync(argThat(request -> request != null && request.side() == FiscalXmlSide.RECEIVED && request.knownPending()));
    }

    // ------------------------------------------------------------------ A5

    /** A5: each stage's calls carry its purpose; a "Sincronizar agora" attempt would be priority instead. */
    @Test
    void eachStageRunsUnderItsPurpose() {
        FiscalXmlSyncControl control = zeroControl(LocalDateTime.of(2026, 11, 26, 9, 0));
        control.setReceivedEventsCheckedOn(LocalDate.of(2026, 11, 26));
        control.setIssuedEventsCheckedOn(LocalDate.of(2026, 11, 26));
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(control));
        List<ProviderCallPurpose> seen = new ArrayList<>();
        when(provider.sync(any())).thenAnswer(inv -> {
            seen.add(ProviderCallContext.current());
            return new FiscalXmlSyncResult(0, 0, 0);
        });
        when(provider.refreshEvents(any(), any(), any(), any())).thenAnswer(inv -> {
            seen.add(ProviderCallContext.current());
            return 0;
        });
        when(provider.syncServiceInvoicesShadow(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            seen.add(ProviderCallContext.current());
            return 0;
        });

        scheduler(LocalDateTime.of(2026, 11, 27, 9, 0)).syncAllCompanies();

        assertThat(seen).containsExactly(ProviderCallPurpose.PAGE, ProviderCallPurpose.EVENTS, ProviderCallPurpose.PAGE,
                ProviderCallPurpose.EVENTS, ProviderCallPurpose.NFSE);
        assertThat(ProviderCallContext.current()).as("nothing left on the thread").isNull();
    }

    @Test
    void theRequestOfAForcedAttemptIsAPriorityOne() {
        List<ProviderCallPurpose> seen = new ArrayList<>();
        ProviderCallContext.priority(true, () -> ProviderCallContext.run(ProviderCallPurpose.EVENTS,
                () -> seen.add(ProviderCallContext.current())));
        ProviderCallContext.priority(false, () -> ProviderCallContext.run(ProviderCallPurpose.EVENTS,
                () -> seen.add(ProviderCallContext.current())));

        assertThat(seen).containsExactly(ProviderCallPurpose.PRIORITY, ProviderCallPurpose.EVENTS);
        assertThat(ProviderCallContext.current()).isNull();
    }

    /** A stage that throws puts the outer scope back - nothing leaks to the next company on the thread. */
    @Test
    void aFailingStageRestoresTheOuterScope() {
        ProviderCallContext.run(ProviderCallPurpose.PAGE, () -> {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProviderCallContext.run(ProviderCallPurpose.EVENTS, () -> {
                throw new IllegalStateException("x");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(ProviderCallContext.current()).isEqualTo(ProviderCallPurpose.PAGE);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProviderCallContext.priority(true, () -> {
                throw new IllegalStateException("y");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(ProviderCallContext.current()).isEqualTo(ProviderCallPurpose.PAGE);
        });
        assertThat(ProviderCallContext.current()).isNull();
    }
}
