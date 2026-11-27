package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.enumerated.CaptureStage;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.repository.CaptureStageStatusRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.capture.CaptureFailureClassifier;
import br.com.cshub.taxify.service.capture.CapturePriorityService;
import br.com.cshub.taxify.service.capture.CaptureStageRecorder;
import br.com.cshub.taxify.service.firmaccess.BlockedPeriodRecoveryService;
import br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Painel da captura: o resultado de cada etapa SIEG gravado pelo agendador (CP-RN-10), sem mudar o fluxo da captura
 * (CT-CP-10, 11, 16 a 19, 65).
 */
@ExtendWith(MockitoExtension.class)
class FiscalXmlSyncSchedulerStagesTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private FiscalXmlSyncControlRepository syncControlRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private FiscalXmlProvider provider;
    @Mock
    private CaptureStageRecorder recorder;

    private FiscalXmlSyncProperties properties;
    private Company company;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        properties = new FiscalXmlSyncProperties();
        properties.setBatchSize(1);
        today = LocalDate.now();
        properties.setReferenceDate(today);
        company = new Company(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        lenient().when(subscriptionRepository
                        .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(any(), any(), any()))
                .thenReturn(Optional.of(subscription));
        lenient().when(companyRepository.findAll()).thenReturn(List.of(company));
        lenient().when(syncControlRepository.findAllCompanyIds()).thenReturn(List.of(company.getId()));
    }

    private FiscalXmlSyncScheduler scheduler(CaptureStageRecorder stageRecorder) {
        return new FiscalXmlSyncScheduler(companyRepository, syncControlRepository, subscriptionRepository, Map.of("sieg", provider),
                properties, FirmAccessTestSupport.allowAll(), null, Clock.system(ZoneId.of("America/Recife")), stageRecorder, null);
    }

    private FiscalXmlSyncControl control(int receivedTotal, int receivedImported, LocalDateTime checkedAt, boolean sweptToday) {
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(company);
        control.setTotalSieg(receivedTotal);
        control.setTotalImported(receivedImported);
        control.setLastCheckedAt(checkedAt);
        control.setIssuedTotalSieg(receivedTotal);
        control.setIssuedTotalImported(receivedImported);
        control.setIssuedCheckedAt(checkedAt);
        if (sweptToday) {
            control.setReceivedEventsCheckedOn(today);
            control.setIssuedEventsCheckedOn(today);
            control.setServiceInvoicesCheckedOn(today);
        }
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(control));
        return control;
    }

    private FiscalXmlSyncScheduler scheduler(BlockedPeriodRecoveryService recovery, CapturePriorityService priority) {
        return new FiscalXmlSyncScheduler(companyRepository, syncControlRepository, subscriptionRepository, Map.of("sieg", provider),
                properties, FirmAccessTestSupport.allowAll(), recovery, Clock.system(ZoneId.of("America/Recife")), recorder, priority);
    }

    /**
     * Review: SIEG_RECOVERY never stays COM_ERRO forever - a piece given up is a warning, and with nothing left to
     * recover an open failure is closed; a failure that will be retried stays a failure.
     */
    @Test
    void recoveryStageClosesWhenNothingIsLeftToTry() {
        control(0, 0, null, true);
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));
        BlockedPeriodRecoveryService recovery = mock(BlockedPeriodRecoveryService.class);
        when(recovery.runNextSiegStepReporting(any(), any()))
                .thenReturn(new BlockedPeriodRecoveryService.SiegStep(true, new IllegalStateException("x"), false))
                .thenReturn(new BlockedPeriodRecoveryService.SiegStep(true, new IllegalStateException("x"), true))
                .thenReturn(new BlockedPeriodRecoveryService.SiegStep(false, null, false));
        FiscalXmlSyncScheduler scheduler = scheduler(recovery, null);

        scheduler.syncAllCompanies();
        verify(recorder).failure(eq(company.getId()), eq(CaptureStage.SIEG_RECOVERY), any(CaptureFailureClassifier.Classified.class));

        control(0, 0, null, true);
        scheduler.syncAllCompanies();
        verify(recorder).success(company.getId(), CaptureStage.SIEG_RECOVERY, CaptureFailureCode.SIEG_RECOVERY_GAVE_UP, null);

        control(0, 0, null, true);
        scheduler.syncAllCompanies();
        verify(recorder).closeFailure(company.getId(), CaptureStage.SIEG_RECOVERY, CaptureFailureCode.SIEG_RECOVERY_GAVE_UP);
    }

    /** Review: the priority requests unavailable - the SIEG round goes on without priority. */
    @Test
    void aFailingPriorityTableNeverStopsTheRound() {
        control(0, 0, null, true);
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));
        CapturePriorityService priority = mock(CapturePriorityService.class);
        when(priority.prepareTick(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("indisponível"));

        assertThatCode(() -> scheduler(null, priority).syncAllCompanies()).doesNotThrowAnyException();

        verify(provider).sync(argThat(request -> request != null && request.side() == FiscalXmlSide.RECEIVED));
        verify(priority, never()).served(any());
        verify(priority, never()).reserved(any());
    }

    /** CT-CP-16: contar-xmls failed and the download went on - SUCCESS with the SIEG_COUNT_FAILED warning. */
    @Test
    void countFailureIsAWarning() {
        control(0, 0, null, true);
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(50, 0, 0, true));

        scheduler(recorder).syncAllCompanies();

        verify(recorder).success(company.getId(), CaptureStage.SIEG_RECEIVED, CaptureFailureCode.SIEG_COUNT_FAILED, null);
        verify(recorder).success(company.getId(), CaptureStage.SIEG_ISSUED, CaptureFailureCode.SIEG_COUNT_FAILED, null);
    }

    /** CT-CP-17: parseErrors = 2 → XML_PARSE_ERRORS with warning_count 2. */
    @Test
    void parseErrorsAreAWarningWithTheCount() {
        control(0, 0, null, true);
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(3, 0, 2));

        scheduler(recorder).syncAllCompanies();

        verify(recorder).success(company.getId(), CaptureStage.SIEG_RECEIVED, CaptureFailureCode.XML_PARSE_ERRORS, 2);
    }

    /** CT-CP-18: a side up to date today is skipped without a call - nothing recorded for it. */
    @Test
    void aSkippedSideRecordsNothing() {
        control(40, 40, LocalDateTime.now(), true);

        scheduler(recorder).syncAllCompanies();

        verify(provider, never()).sync(any());
        verify(recorder, never()).success(any(), eq(CaptureStage.SIEG_RECEIVED), any(), any());
        verify(recorder, never()).success(any(), eq(CaptureStage.SIEG_RECEIVED));
        verify(recorder, never()).failure(any(), any(), any(CaptureFailureClassifier.Classified.class));
    }

    /**
     * CT-CP-19 / CT-CP-10: SIEG_RECEIVED fails with 503 - recorded as SIEG_UNAVAILABLE 503; the issued side, the events
     * and the NFS-e do not run nor record (the flow is unchanged).
     */
    @Test
    void aFailingSideAbortsTheRestAsBeforeAndRecordsOnlyItsStage() {
        control(0, 0, null, false);
        when(provider.sync(any())).thenThrow(new SiegIntegrationException("error.sieg.integration.failed", "x", 503));

        scheduler(recorder).syncAllCompanies();

        ArgumentCaptor<CaptureFailureClassifier.Classified> classified = ArgumentCaptor.forClass(CaptureFailureClassifier.Classified.class);
        verify(recorder).failure(eq(company.getId()), eq(CaptureStage.SIEG_RECEIVED), classified.capture());
        assertThat(classified.getValue().code()).isEqualTo(CaptureFailureCode.SIEG_UNAVAILABLE);
        assertThat(classified.getValue().providerStatus()).isEqualTo("503");
        verify(provider, never()).sync(argThat(request -> request != null && request.side() == FiscalXmlSide.ISSUED));
        verify(provider, never()).refreshEvents(any(), any(), any(), any());
        verify(provider, never()).syncServiceInvoices(any(), any(), any());
        verify(recorder, never()).failure(any(), eq(CaptureStage.SIEG_ISSUED), any(CaptureFailureClassifier.Classified.class));
        verify(recorder, never()).success(any(), eq(CaptureStage.SIEG_EVENTS_RECEIVED));
    }

    /** Events and NFS-e record their own stage when they run. */
    @Test
    void eventsAndServiceInvoicesRecordTheirStage() {
        control(0, 0, null, false);
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(0, 0, 0));
        lenient().when(provider.refreshEvents(any(), eq(FiscalXmlSide.ISSUED), any(), any())).thenThrow(new IllegalStateException("x"));

        scheduler(recorder).syncAllCompanies();

        verify(recorder).success(company.getId(), CaptureStage.SIEG_EVENTS_RECEIVED);
        verify(recorder).failure(eq(company.getId()), eq(CaptureStage.SIEG_EVENTS_ISSUED), any(CaptureFailureClassifier.Classified.class));
        verify(recorder).success(company.getId(), CaptureStage.SIEG_NFSE);
    }

    /** A company without a CNPJ: COMPANY_WITHOUT_CNPJ, decided by the company itself. */
    @Test
    void aCompanyWithoutCnpj() {
        company.setDocument(null);
        control(0, 0, null, true);
        when(provider.sync(any())).thenThrow(new NullPointerException());

        scheduler(recorder).syncAllCompanies();

        ArgumentCaptor<CaptureFailureClassifier.Classified> classified = ArgumentCaptor.forClass(CaptureFailureClassifier.Classified.class);
        verify(recorder).failure(eq(company.getId()), eq(CaptureStage.SIEG_RECEIVED), classified.capture());
        assertThat(classified.getValue().code()).isEqualTo(CaptureFailureCode.COMPANY_WITHOUT_CNPJ);
    }

    /**
     * CT-CP-65 / CA-CP-24: the stage table is unavailable - the recorder only logs, the capture goes on (the provider
     * stored the notes, the next stages run) and nothing reaches the scheduler.
     */
    @Test
    void aFailureToRecordNeverStopsTheCapture() {
        CaptureStageStatusRepository broken = mock(CaptureStageStatusRepository.class);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("capture_stage_status indisponível"))
                .when(broken).upsertSuccess(anyString(), anyString(), anyString(), any(), any(), any());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("capture_stage_status indisponível"))
                .when(broken).upsertFailure(anyString(), anyString(), anyString(), any(), any(), any());
        CaptureStageRecorder real = new CaptureStageRecorder(broken, Clock.system(ZoneId.of("America/Recife")));
        control(0, 0, null, false);
        when(provider.sync(any())).thenReturn(new FiscalXmlSyncResult(5, 0, 0));

        assertThatCode(() -> scheduler(real).syncAllCompanies()).doesNotThrowAnyException();

        verify(provider).sync(argThat(request -> request != null && request.side() == FiscalXmlSide.RECEIVED));
        verify(provider).sync(argThat(request -> request != null && request.side() == FiscalXmlSide.ISSUED));
        verify(provider).syncServiceInvoices(any(), any(), any());
        verify(syncControlRepository).markServiceInvoicesChecked(any(), eq(today));
        assertThatCode(() -> real.failure(company.getId(), CaptureStage.SIEG_NFSE, CaptureFailureCode.SIEG_TIMEOUT, null))
                .doesNotThrowAnyException();
    }

    /** The recorder's own validation: provider_status sanitized before it reaches the database (CT-CP-15). */
    @Test
    void theRecorderSanitizesTheProviderStatus() {
        CaptureStageStatusRepository repository = mock(CaptureStageStatusRepository.class);
        CaptureStageRecorder real = new CaptureStageRecorder(repository, Clock.system(ZoneId.of("America/Recife")));

        real.failure(company.getId(), CaptureStage.SIEG_RECEIVED, CaptureFailureCode.SIEG_REJECTED, "4O0");
        real.failure(company.getId(), CaptureStage.SIEG_RECEIVED, CaptureFailureCode.SIEG_REJECTED, "400");

        verify(repository).upsertFailure(anyString(), eq(company.getId().toString()), eq("SIEG_RECEIVED"), any(),
                eq("SIEG_REJECTED"), eq((String) null));
        verify(repository).upsertFailure(anyString(), eq(company.getId().toString()), eq("SIEG_RECEIVED"), any(),
                eq("SIEG_REJECTED"), eq("400"));
        verify(repository, never()).upsertSuccess(anyString(), anyString(), anyString(), any(), any(), any());
    }
}
