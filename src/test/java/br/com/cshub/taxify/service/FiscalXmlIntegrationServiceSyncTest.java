package br.com.cshub.taxify.service;

import br.com.cshub.taxify.integration.fiscalxml.FiscalEventParser;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.integration.sieg.SiegBaixarXmlsRequest;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.integration.sieg.SiegDocumentCounts;
import br.com.cshub.taxify.integration.sieg.SiegProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiscalXmlIntegrationServiceSyncTest {

    @Mock
    private SiegClient siegClient;
    @Mock
    private FiscalXmlDocumentRepository xmlDocumentRepository;
    @Mock
    private FiscalXmlSyncControlRepository syncControlRepository;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private MessageService messageService;
    @Mock
    private FiscalXmlEventService eventService;
    @Mock
    private FirmIntegrationService firmIntegrationService;

    private FiscalXmlIntegrationService service;
    private UUID companyId;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        Company company = new Company(companyId);
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
        lenient().when(firmIntegrationService.getApiKey(firm.getId(), IntegrationType.SIEG)).thenReturn("test-api-key");
        company.setAccountingFirm(firm);
        lenient().when(companyValidation.checkExist(companyId)).thenReturn(company);
        lenient().when(messageService.getMessage(any())).thenReturn("error");
        lenient().when(syncControlRepository.findByCompany_Id(companyId)).thenReturn(Optional.empty());

        SiegProperties properties = new SiegProperties();

        FiscalXmlSyncProperties syncProperties = new FiscalXmlSyncProperties();
        syncProperties.setStoragePath(tempDir.toString());

        service = new FiscalXmlIntegrationService(siegClient, new FiscalXmlMetadataParser(),
                new FiscalXmlFileStorage(syncProperties, messageService), new FiscalXmlPackageExtractor(),
                xmlDocumentRepository, syncControlRepository, companyValidation, messageService, properties, firmIntegrationService,
                new FiscalEventParser(), eventService,
                org.mockito.Mockito.mock(br.com.cshub.taxify.repository.SefazNoteSummaryRepository.class),
                org.mockito.Mockito.mock(ServiceInvoiceService.class));
    }

    @Test
    void issuedSideAsksSiegByIssuerAndKeepsItsOwnBookkeeping() {
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(7, 0));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(true), any(), any())).thenReturn(4L);

        service.syncFromSieg(filter(null, null, FiscalXmlSide.ISSUED));

        ArgumentCaptor<SiegBaixarXmlsRequest> request = ArgumentCaptor.forClass(SiegBaixarXmlsRequest.class);
        verify(siegClient).contarXmls(request.capture(), any());
        assertThat(request.getValue().cnpjEmit()).isEqualTo("11594485000105");
        assertThat(request.getValue().cnpjDest()).isNull();

        ArgumentCaptor<FiscalXmlSyncControl> control = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(control.capture());
        assertThat(control.getValue().getIssuedTotalSieg()).isEqualTo(7);
        assertThat(control.getValue().getIssuedTotalImported()).isEqualTo(4);
        assertThat(control.getValue().getIssuedCheckedAt()).isNotNull();
        assertThat(control.getValue().getTotalSieg()).as("received side untouched").isZero();
    }

    @Test
    void manualSyncWithoutSideRunsBothSides() {
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(0, 0));

        service.syncFromSieg(filter(null, null, null));

        ArgumentCaptor<SiegBaixarXmlsRequest> request = ArgumentCaptor.forClass(SiegBaixarXmlsRequest.class);
        verify(siegClient, org.mockito.Mockito.times(2)).contarXmls(request.capture(), any());
        assertThat(request.getAllValues()).extracting(SiegBaixarXmlsRequest::cnpjDest).containsExactly("11594485000105", null);
        assertThat(request.getAllValues()).extracting(SiegBaixarXmlsRequest::cnpjEmit).containsExactly(null, "11594485000105");
    }

    @Test
    void skipsBaixarXmlsWhenSiegReportsZeroPendingNfe() {
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(0, 0));

        XmlDownloadResultDTO result = service.syncFromSieg(filter());

        verify(siegClient, never()).baixarXmls(any(), any());
        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.xmls()).isEmpty();
    }

    @Test
    void callsBaixarXmlsWhenSiegReportsPendingNfe() {
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(3, 0));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());

        service.syncFromSieg(filter());

        verify(siegClient).baixarXmls(any(), any());
    }

    @Test
    void stillCallsBaixarXmlsWhenTheCountCheckItselfFails() {
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenThrow(new RuntimeException("boom"));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());

        service.syncFromSieg(filter());

        verify(siegClient).baixarXmls(any(), any());
        verify(syncControlRepository, never()).save(any());
    }

    @Test
    void updatesSyncControlWithSiegTotalAndActualPersistedCountWhenCountSucceeds() {
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(200, 0));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(false), any(), any())).thenReturn(100L);

        LocalDate inicio = LocalDate.of(2026, 9, 10);
        LocalDate fim = LocalDate.of(2026, 9, 11);
        service.syncFromSieg(filter(inicio, fim));

        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        FiscalXmlSyncControl control = captor.getValue();
        assertThat(control.getCompany().getId()).isEqualTo(companyId);
        assertThat(control.getIssueDateFrom()).isEqualTo(inicio);
        assertThat(control.getIssueDateTo()).isEqualTo(fim);
        assertThat(control.getTotalSieg()).isEqualTo(200);
        assertThat(control.getTotalImported()).isEqualTo(100);
        assertThat(control.getLastCheckedAt()).isNotNull();
    }

    @Test
    void totalImportadoIsScopedToTheSyncWindowNotTheCompanysLifetimeTotal() {
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(30, 0));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(false), any(), any())).thenReturn(30L);
        // Lifetime total across every month ever imported, deliberately much bigger than
        // the window-scoped count above - if updateSyncControl ever regresses back to
        // countByCompany_Id, this makes it obvious via a wrong totalImportado.
        lenient().when(xmlDocumentRepository.countByCompany_Id(companyId)).thenReturn(9999L);

        service.syncFromSieg(filter(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));

        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalImported()).isEqualTo(30);
    }

    @Test
    void skipsTheCountCheckAndGoesStraightToDownloadWhenSkipCountCheckIsTrue() {
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(false), any(), any())).thenReturn(50L);

        service.syncFromSieg(filter(), true);

        verify(siegClient, never()).contarXmls(any(), any());
        verify(siegClient).baixarXmls(any(), any());
    }

    /**
     * B1 replaces RN-08's "total = imported": SIEG said 200, the pass from the first page ended with 120 delivered - SIEG's
     * total stays 200 and the 80 are kept as the month's known difference (the scheduler counts the side as up to date).
     */
    @Test
    void aCompletePassThatEndsShortKeepsTheDifferenceNotATotalOfItsOwn() {
        FiscalXmlSyncControl existing = new FiscalXmlSyncControl();
        existing.setId(UUID.randomUUID());
        existing.setTotalSieg(200);
        when(syncControlRepository.findByCompany_Id(companyId)).thenReturn(Optional.of(existing));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(false), any(), any())).thenReturn(120L);
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetween(eq(companyId), eq(false), any(), any())).thenReturn(130L);

        service.syncFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27)), true);

        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        FiscalXmlSyncControl control = captor.getValue();
        assertThat(control.getTotalSieg()).isEqualTo(200);
        assertThat(control.getTotalImported()).isEqualTo(120);
        assertThat(control.knownGap(false, "2026-11")).isEqualTo(80);
        assertThat(control.getReceivedStoredAnySource()).as("the panel's 'no Taxify (qualquer origem)'").isEqualTo(130);
        assertThat(control.getLastCheckedAt()).isNotNull();
    }

    /** B1: the paging ended short after skip 120 - a reconciliation is asked for, to run outside this transaction. */
    @Test
    void aShortEndAfterTheFirstPageAsksForAReconciliation() {
        FiscalXmlSyncControl existing = new FiscalXmlSyncControl();
        existing.setId(UUID.randomUUID());
        existing.setTotalSieg(200);
        when(syncControlRepository.findByCompany_Id(companyId)).thenReturn(Optional.of(existing));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(false), any(), any())).thenReturn(120L);

        FiscalXmlIntegrationService.SiegSideOutcome outcome = service.syncSideFromSieg(
                filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27)), true);

        assertThat(outcome.reconciliation()).isEqualTo(new FiscalXmlIntegrationService.ReconciliationNeed(companyId,
                FiscalXmlSide.RECEIVED, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), 200, 120, true));
        assertThat(outcome.reconciliation().pages()).isEqualTo(4);
        verify(siegClient).baixarXmls(any(), any()); // only the regular page here
    }

    /** B1: the same difference the month's last complete pass left (80) - no reconciliation again. */
    @Test
    void theSameDifferenceAsTheLastPassIsNotReconciledAgain() {
        FiscalXmlSyncControl existing = new FiscalXmlSyncControl();
        existing.setId(UUID.randomUUID());
        existing.setTotalSieg(200);
        existing.recordKnownGap(false, "2026-11", 80);
        when(syncControlRepository.findByCompany_Id(companyId)).thenReturn(Optional.of(existing));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(false), any(), any())).thenReturn(120L);

        FiscalXmlIntegrationService.SiegSideOutcome outcome = service.syncSideFromSieg(
                filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27)), true);

        assertThat(outcome.reconciliation()).isNull();
    }

    /** The count never inherits the page's skip: always the whole window from 0. */
    @Test
    void theCountIsAlwaysFromSkipZero() {
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(false), any(), any())).thenReturn(20L);
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(20, 0));

        service.syncSideFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27)), false);

        ArgumentCaptor<SiegBaixarXmlsRequest> count = ArgumentCaptor.forClass(SiegBaixarXmlsRequest.class);
        verify(siegClient).contarXmls(count.capture(), any());
        assertThat(count.getValue().skip()).isZero();
        verify(siegClient, never()).baixarXmls(any(), any()); // 20 counted, 20 delivered
    }

    @Test
    void reusesTheExistingSyncControlRowInsteadOfCreatingANewOne() {
        FiscalXmlSyncControl existing = new FiscalXmlSyncControl();
        existing.setId(UUID.randomUUID());
        when(syncControlRepository.findByCompany_Id(companyId)).thenReturn(Optional.of(existing));
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(0, 0));

        service.syncFromSieg(filter());

        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
    }

    /** A2: no note of the side issued in the sweep window (one day earlier locally: SIEG days are UTC) - SIEG is not asked. */
    @Test
    void theEventSweepOfASideWithoutRecentNotesCostsNoRequest() {
        LocalDate from = LocalDate.of(2026, 11, 25);
        LocalDate to = LocalDate.of(2026, 11, 27);
        when(xmlDocumentRepository.existsByCompany_IdAndOwnIssueAndIssueDateBetween(companyId, true, from.minusDays(1).atStartOfDay(),
                to.atTime(java.time.LocalTime.MAX))).thenReturn(false);

        assertThat(service.refreshRecentEvents(companyId, FiscalXmlSide.ISSUED, from, to)).isZero();

        verify(siegClient, never()).baixarXmls(any(), any());
    }

    /** A2: a side with a note issued in the window is swept as before, from the first page. */
    @Test
    void theEventSweepOfASideWithRecentNotesAsksSieg() {
        LocalDate from = LocalDate.of(2026, 11, 25);
        LocalDate to = LocalDate.of(2026, 11, 27);
        when(xmlDocumentRepository.existsByCompany_IdAndOwnIssueAndIssueDateBetween(eq(companyId), eq(false), any(), any())).thenReturn(true);
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());

        service.refreshRecentEvents(companyId, FiscalXmlSide.RECEIVED, from, to);

        ArgumentCaptor<SiegBaixarXmlsRequest> request = ArgumentCaptor.forClass(SiegBaixarXmlsRequest.class);
        verify(siegClient).baixarXmls(request.capture(), any());
        assertThat(request.getValue().skip()).isZero();
        assertThat(request.getValue().cnpjDest()).isEqualTo("11594485000105");
        assertThat(request.getValue().baixarEventos()).isTrue();
    }

    /** A2 off: every side is swept, notes or not. */
    @Test
    void withTheEventGateOffEverySideIsSwept() {
        SiegProperties ungated = new SiegProperties();
        ungated.setEventsOnlyWithRecentNotes(false);
        FiscalXmlSyncProperties syncProperties = new FiscalXmlSyncProperties();
        syncProperties.setStoragePath(tempDir.toString());
        FiscalXmlIntegrationService everySide = new FiscalXmlIntegrationService(siegClient, new FiscalXmlMetadataParser(),
                new FiscalXmlFileStorage(syncProperties, messageService), new FiscalXmlPackageExtractor(),
                xmlDocumentRepository, syncControlRepository, companyValidation, messageService, ungated, firmIntegrationService,
                new FiscalEventParser(), eventService,
                org.mockito.Mockito.mock(br.com.cshub.taxify.repository.SefazNoteSummaryRepository.class),
                org.mockito.Mockito.mock(ServiceInvoiceService.class));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());

        everySide.refreshRecentEvents(companyId, FiscalXmlSide.ISSUED, LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 27));

        verify(siegClient).baixarXmls(any(), any());
        verify(xmlDocumentRepository, never()).existsByCompany_IdAndOwnIssueAndIssueDateBetween(any(), any(), any(), any());
    }

    /** A1: the side's count also brings the NFS-e count of the same filter - handed to the NFS-e check, no request more. */
    @Test
    void theSidesCountCarriesTheNfseCount() {
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(0, 9));

        FiscalXmlIntegrationService.SiegSideOutcome outcome = service.syncSideFromSieg(filter(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 27), FiscalXmlSide.RECEIVED), false);

        assertThat(outcome.serviceInvoiceCount()).isEqualTo(9);
        assertThat(service.syncSideFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), FiscalXmlSide.RECEIVED), true)
                .serviceInvoiceCount()).as("not counted: unknown").isNull();
    }

    /** B1: the scheduler's skip is what SIEG itself delivered of the side's window, never the filter's (any source). */
    @Test
    void theSchedulersSkipIsWhatSiegDelivered() {
        when(xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(false), any(), any()))
                .thenReturn(20L);
        when(siegClient.contarXmls(any(SiegBaixarXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(100, 0));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());

        XmlDownloadFilterDTO anySource = new XmlDownloadFilterDTO(companyId.toString(), 1, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 27), 50, 50, null, FiscalXmlSide.RECEIVED);
        service.syncSideFromSieg(anySource, false);

        ArgumentCaptor<SiegBaixarXmlsRequest> request = ArgumentCaptor.forClass(SiegBaixarXmlsRequest.class);
        verify(siegClient, org.mockito.Mockito.atLeastOnce()).baixarXmls(request.capture(), any());
        assertThat(request.getAllValues().getFirst().skip()).isEqualTo(20);
    }

    private XmlDownloadFilterDTO filter() {
        return filter(null, null);
    }

    /** The received side, as the scheduler calls it - the existing tests are about that side. */
    private XmlDownloadFilterDTO filter(LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim) {
        return filter(dataEmissaoInicio, dataEmissaoFim, FiscalXmlSide.RECEIVED);
    }

    private XmlDownloadFilterDTO filter(LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim, FiscalXmlSide side) {
        return new XmlDownloadFilterDTO(companyId.toString(), 1, dataEmissaoInicio, dataEmissaoFim, null, null, null, side);
    }
}
