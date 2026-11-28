package br.com.cshub.taxify.service;

import br.com.cshub.taxify.support.TestNotes;
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
import br.com.cshub.taxify.integration.sieg.SiegDownloadXmlsRequest;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.integration.sieg.SiegDocumentCounts;
import br.com.cshub.taxify.integration.sieg.SiegProperties;
import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.support.InMemoryCaptureCounts;
import java.time.YearMonth;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    private FiscalDocumentRepository documentRepository;
    @Mock
    private GoodsInvoiceRepository goodsInvoiceRepository;
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
    private Company company;
    private final InMemoryCaptureCounts counts = new InMemoryCaptureCounts();
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        company = new Company(companyId);
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
                documentRepository, goodsInvoiceRepository, counts.service(), syncControlRepository, companyValidation, messageService,
                properties, firmIntegrationService,
                new FiscalEventParser(), eventService,
                org.mockito.Mockito.mock(br.com.cshub.taxify.repository.SefazNoteSummaryRepository.class),
                org.mockito.Mockito.mock(ServiceInvoiceService.class));
    }

    @Test
    void issuedSideAsksSiegByIssuerAndKeepsItsOwnBookkeeping() {
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(7, 0));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());
        when(goodsInvoiceRepository.countDeliveredBy(eq(companyId), eq(true), any(), any(), any(), any())).thenReturn(4L);

        service.syncFromSieg(filter(NOVEMBER.atDay(1), NOVEMBER.atDay(27), FiscalXmlSide.ISSUED));

        ArgumentCaptor<SiegDownloadXmlsRequest> request = ArgumentCaptor.forClass(SiegDownloadXmlsRequest.class);
        verify(siegClient).countXmls(request.capture(), any());
        assertThat(request.getValue().cnpjEmit()).isEqualTo("11594485000105");
        assertThat(request.getValue().cnpjDest()).isNull();

        CapturePeriodCount issued = counts.require(companyId, CaptureSide.ISSUED, NOVEMBER);
        assertThat(issued.getProviderTotal()).isEqualTo(7);
        assertThat(issued.getImportedTotal()).isEqualTo(4);
        assertThat(issued.getImportedCountedAt()).isNotNull();
        assertThat(counts.row(companyId, CaptureSide.RECEIVED, NOVEMBER)).as("received side untouched").isEmpty();
    }

    @Test
    void manualSyncWithoutSideRunsBothSides() {
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(0, 0));

        service.syncFromSieg(filter(null, null, null));

        ArgumentCaptor<SiegDownloadXmlsRequest> request = ArgumentCaptor.forClass(SiegDownloadXmlsRequest.class);
        verify(siegClient, org.mockito.Mockito.times(2)).countXmls(request.capture(), any());
        assertThat(request.getAllValues()).extracting(SiegDownloadXmlsRequest::cnpjDest).containsExactly("11594485000105", null);
        assertThat(request.getAllValues()).extracting(SiegDownloadXmlsRequest::cnpjEmit).containsExactly(null, "11594485000105");
    }

    @Test
    void skipsBaixarXmlsWhenSiegReportsZeroPendingNfe() {
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(0, 0));

        XmlDownloadResultDTO result = service.syncFromSieg(filter());

        verify(siegClient, never()).downloadXmls(any(), any());
        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.xmls()).isEmpty();
    }

    @Test
    void callsBaixarXmlsWhenSiegReportsPendingNfe() {
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(3, 0));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());

        service.syncFromSieg(filter());

        verify(siegClient).downloadXmls(any(), any());
    }

    @Test
    void stillCallsBaixarXmlsWhenTheCountCheckItselfFails() {
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenThrow(new RuntimeException("boom"));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());

        service.syncFromSieg(filter());

        verify(siegClient).downloadXmls(any(), any());
        assertThat(counts.rows()).as("no count recorded").isEmpty();
    }

    @Test
    void updatesSyncControlWithSiegTotalAndActualPersistedCountWhenCountSucceeds() {
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(200, 0));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());
        when(goodsInvoiceRepository.countDeliveredBy(eq(companyId), eq(false), any(), any(), any(), any())).thenReturn(100L);

        LocalDate inicio = LocalDate.of(2026, 9, 10);
        LocalDate fim = LocalDate.of(2026, 9, 11);
        service.syncFromSieg(filter(inicio, fim));

        CapturePeriodCount control = counts.require(companyId, CaptureSide.RECEIVED, YearMonth.of(2026, 9));
        assertThat(control.getCompany().getId()).isEqualTo(companyId);
        assertThat(control.getWindowStart()).isEqualTo(inicio);
        assertThat(control.getWindowEnd()).isEqualTo(fim);
        assertThat(control.getProviderTotal()).isEqualTo(200);
        assertThat(control.getImportedTotal()).isEqualTo(100);
        assertThat(control.getImportedCountedAt()).isNotNull();
    }

    @Test
    void totalImportadoIsScopedToTheSyncWindowNotTheCompanysLifetimeTotal() {
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(30, 0));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());
        when(goodsInvoiceRepository.countDeliveredBy(eq(companyId), eq(false), any(), any(), any(), any())).thenReturn(30L);
        // Lifetime total across every month ever imported, deliberately much bigger than
        // the window-scoped count above - if updateSyncControl ever regresses back to
        // countByCompany_Id, this makes it obvious via a wrong totalImportado.


        service.syncFromSieg(filter(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));

        assertThat(counts.require(companyId, CaptureSide.RECEIVED, YearMonth.of(2026, 9)).getImportedTotal()).isEqualTo(30);
    }

    @Test
    void skipsTheCountCheckAndGoesStraightToDownloadWhenSkipCountCheckIsTrue() {
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());
        when(goodsInvoiceRepository.countDeliveredBy(eq(companyId), eq(false), any(), any(), any(), any())).thenReturn(50L);

        service.syncFromSieg(filter(), true);

        verify(siegClient, never()).countXmls(any(), any());
        verify(siegClient).downloadXmls(any(), any());
    }

    /**
     * B1 replaces RN-08's "total = imported": SIEG said 200, the pass from the first page ended with 120 delivered - SIEG's
     * total stays 200 and the 80 are kept as the month's known difference (the scheduler counts the side as up to date).
     */
    @Test
    void aCompletePassThatEndsShortKeepsTheDifferenceNotATotalOfItsOwn() {
        counts.seed(company, CaptureSide.RECEIVED, NOVEMBER, 200, 0, NOVEMBER.atDay(26).atTime(9, 0));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());
        when(goodsInvoiceRepository.countDeliveredBy(eq(companyId), eq(false), any(), any(), any(), any())).thenReturn(120L);
        when(goodsInvoiceRepository.countStored(eq(companyId), eq(false), any(), any())).thenReturn(130L);

        service.syncFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27)), true);

        CapturePeriodCount control = counts.require(companyId, CaptureSide.RECEIVED, NOVEMBER);
        assertThat(control.getProviderTotal()).isEqualTo(200);
        assertThat(control.getImportedTotal()).isEqualTo(120);
        assertThat(counts.of(company).knownGap(CaptureSide.RECEIVED, NOVEMBER)).isEqualTo(80);
        assertThat(control.getStoredAnySourceTotal()).as("the panel's 'no Taxify (qualquer origem)'").isEqualTo(130);
        assertThat(control.getImportedCountedAt()).isNotNull();
    }

    /** B1: the paging ended short after skip 120 - a reconciliation is asked for, to run outside this transaction. */
    @Test
    void aShortEndAfterTheFirstPageAsksForAReconciliation() {
        counts.seed(company, CaptureSide.RECEIVED, NOVEMBER, 200, 0, NOVEMBER.atDay(26).atTime(9, 0));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());
        when(goodsInvoiceRepository.countDeliveredBy(eq(companyId), eq(false), any(), any(), any(), any())).thenReturn(120L);

        FiscalXmlIntegrationService.SiegSideOutcome outcome = service.syncSideFromSieg(
                filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27)), true);

        assertThat(outcome.reconciliation()).isEqualTo(new FiscalXmlIntegrationService.ReconciliationNeed(companyId,
                FiscalXmlSide.RECEIVED, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), 200, 120, true));
        assertThat(outcome.reconciliation().pages()).isEqualTo(4);
        verify(siegClient).downloadXmls(any(), any()); // only the regular page here
    }

    /** B1: the same difference the month's last complete pass left (80) - no reconciliation again. */
    @Test
    void theSameDifferenceAsTheLastPassIsNotReconciledAgain() {
        counts.seed(company, CaptureSide.RECEIVED, NOVEMBER, 200, 0, NOVEMBER.atDay(26).atTime(9, 0));
        counts.gap(company, CaptureSide.RECEIVED, NOVEMBER, 80);
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());
        when(goodsInvoiceRepository.countDeliveredBy(eq(companyId), eq(false), any(), any(), any(), any())).thenReturn(120L);

        FiscalXmlIntegrationService.SiegSideOutcome outcome = service.syncSideFromSieg(
                filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27)), true);

        assertThat(outcome.reconciliation()).isNull();
    }

    /** The count never inherits the page's skip: always the whole window from 0. */
    @Test
    void theCountIsAlwaysFromSkipZero() {
        when(goodsInvoiceRepository.countDeliveredBy(eq(companyId), eq(false), any(), any(), any(), any())).thenReturn(20L);
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(20, 0));

        service.syncSideFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27)), false);

        ArgumentCaptor<SiegDownloadXmlsRequest> count = ArgumentCaptor.forClass(SiegDownloadXmlsRequest.class);
        verify(siegClient).countXmls(count.capture(), any());
        assertThat(count.getValue().skip()).isZero();
        verify(siegClient, never()).downloadXmls(any(), any()); // 20 counted, 20 delivered
    }

    @Test
    void reusesTheExistingSyncControlRowInsteadOfCreatingANewOne() {
        FiscalXmlSyncControl existing = new FiscalXmlSyncControl();
        existing.setId(UUID.randomUUID());
        when(syncControlRepository.findByCompany_Id(companyId)).thenReturn(Optional.of(existing));
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(0, 0));

        service.syncFromSieg(filter());

        // the queue row is not recreated (the counts go to capture_period_counts)
        verify(syncControlRepository, never()).save(any());
    }

    /** A2: no note of the side issued in the sweep window (one day earlier locally: SIEG days are UTC) - SIEG is not asked. */
    @Test
    void theEventSweepOfASideWithoutRecentNotesCostsNoRequest() {
        LocalDate from = LocalDate.of(2026, 11, 25);
        LocalDate to = LocalDate.of(2026, 11, 27);
        when(goodsInvoiceRepository.existsIssuedBetween(companyId, true, from.minusDays(1).atStartOfDay(),
                to.atTime(java.time.LocalTime.MAX))).thenReturn(false);

        assertThat(service.refreshRecentEvents(companyId, FiscalXmlSide.ISSUED, from, to)).isZero();

        verify(siegClient, never()).downloadXmls(any(), any());
    }

    /** A2: a side with a note issued in the window is swept as before, from the first page. */
    @Test
    void theEventSweepOfASideWithRecentNotesAsksSieg() {
        LocalDate from = LocalDate.of(2026, 11, 25);
        LocalDate to = LocalDate.of(2026, 11, 27);
        when(goodsInvoiceRepository.existsIssuedBetween(eq(companyId), eq(false), any(), any())).thenReturn(true);
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());

        service.refreshRecentEvents(companyId, FiscalXmlSide.RECEIVED, from, to);

        ArgumentCaptor<SiegDownloadXmlsRequest> request = ArgumentCaptor.forClass(SiegDownloadXmlsRequest.class);
        verify(siegClient).downloadXmls(request.capture(), any());
        assertThat(request.getValue().skip()).isZero();
        assertThat(request.getValue().cnpjDest()).isEqualTo("11594485000105");
        assertThat(request.getValue().includeEvents()).isTrue();
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
                documentRepository, goodsInvoiceRepository, counts.service(), syncControlRepository, companyValidation, messageService,
                ungated, firmIntegrationService,
                new FiscalEventParser(), eventService,
                org.mockito.Mockito.mock(br.com.cshub.taxify.repository.SefazNoteSummaryRepository.class),
                org.mockito.Mockito.mock(ServiceInvoiceService.class));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());

        everySide.refreshRecentEvents(companyId, FiscalXmlSide.ISSUED, LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 27));

        verify(siegClient).downloadXmls(any(), any());
        verify(goodsInvoiceRepository, never()).existsIssuedBetween(any(), anyBoolean(), any(), any());
    }

    /** A1: the side's count also brings the NFS-e count of the same filter - handed to the NFS-e check, no request more. */
    @Test
    void theSidesCountCarriesTheNfseCount() {
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(0, 9));

        FiscalXmlIntegrationService.SiegSideOutcome outcome = service.syncSideFromSieg(filter(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 27), FiscalXmlSide.RECEIVED), false);

        assertThat(outcome.serviceInvoiceCount()).isEqualTo(9);
        assertThat(service.syncSideFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), FiscalXmlSide.RECEIVED), true)
                .serviceInvoiceCount()).as("not counted: unknown").isNull();
    }

    /** B1: the scheduler's skip is what SIEG itself delivered of the side's window, never the filter's (any source). */
    @Test
    void theSchedulersSkipIsWhatSiegDelivered() {
        when(goodsInvoiceRepository.countDeliveredBy(eq(companyId), eq(false), any(), any(), any(), any()))
                .thenReturn(20L);
        when(siegClient.countXmls(any(SiegDownloadXmlsRequest.class), any())).thenReturn(new SiegDocumentCounts(100, 0));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());

        XmlDownloadFilterDTO anySource = new XmlDownloadFilterDTO(companyId.toString(), 1, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 27), 50, 50, null, FiscalXmlSide.RECEIVED);
        service.syncSideFromSieg(anySource, false);

        ArgumentCaptor<SiegDownloadXmlsRequest> request = ArgumentCaptor.forClass(SiegDownloadXmlsRequest.class);
        verify(siegClient, org.mockito.Mockito.atLeastOnce()).downloadXmls(request.capture(), any());
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
