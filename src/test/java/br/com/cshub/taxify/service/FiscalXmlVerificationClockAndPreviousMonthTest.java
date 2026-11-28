package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.fiscaldocument.CaptureCounts;
import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventParser;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.integration.sieg.SiegDocumentCounts;
import br.com.cshub.taxify.integration.sieg.SiegProperties;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.support.InMemoryCaptureCounts;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B-CP-1 (CP-RN-21, CT-CP-64): the verification times in Recife even with the JVM's clock elsewhere; B-CP-6 (CP-RN-22,
 * CT-CP-66 a 68): the previous month's counters kept on the turn of the month - now one row per side and month in
 * {@code capture_period_counts} (FD-RN-32), the closed month the previous one.
 */
class FiscalXmlVerificationClockAndPreviousMonthTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final YearMonth DECEMBER = YearMonth.of(2026, 12);

    @TempDir
    Path tempDir;

    private SiegClient siegClient;
    private GoodsInvoiceRepository goodsInvoices;
    private FiscalXmlSyncControlRepository controls;
    private final InMemoryCaptureCounts counts = new InMemoryCaptureCounts();
    private FiscalXmlIntegrationService service;
    private UUID companyId;
    private Company company;

    @BeforeEach
    void setUp() {
        siegClient = mock(SiegClient.class);
        goodsInvoices = mock(GoodsInvoiceRepository.class);
        controls = mock(FiscalXmlSyncControlRepository.class);
        CompanyValidation validation = mock(CompanyValidation.class);
        MessageService messages = mock(MessageService.class);
        FirmIntegrationService integrations = mock(FirmIntegrationService.class);
        companyId = UUID.randomUUID();
        company = new Company(companyId);
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
        company.setAccountingFirm(firm);
        when(validation.checkExist(companyId)).thenReturn(company);
        when(integrations.getApiKey(firm.getId(), IntegrationType.SIEG)).thenReturn("chave-ficticia");
        when(controls.findByCompany_Id(companyId)).thenReturn(Optional.empty());
        FiscalXmlSyncProperties syncProperties = new FiscalXmlSyncProperties();
        syncProperties.setStoragePath(tempDir.toString());
        service = new FiscalXmlIntegrationService(siegClient, new FiscalXmlMetadataParser(),
                new FiscalXmlFileStorage(syncProperties, messages), new FiscalXmlPackageExtractor(), mock(FiscalDocumentRepository.class),
                goodsInvoices, counts.service(), controls, validation, messages, new SiegProperties(), integrations,
                new FiscalEventParser(), mock(FiscalXmlEventService.class), mock(SefazNoteSummaryRepository.class),
                mock(ServiceInvoiceService.class));
    }

    private XmlDownloadFilterDTO filter(LocalDate from, LocalDate to, FiscalXmlSide side) {
        return new XmlDownloadFilterDTO(companyId.toString(), 1, from, to, 50, 0, null, side);
    }

    /** CT-CP-64: 13:00 UTC = 10:00 in Recife - the verification is stored as 10:00, not 13:00. */
    @Test
    void verificationTimeIsRecifeEvenWithTheJvmInUtc() {
        service.setClock(Clock.fixed(Instant.parse("2026-11-27T13:00:00Z"), ZoneId.of("America/Recife")));
        when(siegClient.countXmls(any(), any())).thenReturn(new SiegDocumentCounts(0, 0));

        service.syncFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), FiscalXmlSide.ISSUED));
        service.syncFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), FiscalXmlSide.RECEIVED));

        assertThat(counts.require(companyId, CaptureSide.ISSUED, NOVEMBER).getImportedCountedAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0));
        assertThat(counts.require(companyId, CaptureSide.RECEIVED, NOVEMBER).getImportedCountedAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0));
    }

    /** CT-CP-66 / CA-CP-26: Y, November 180 of 200; the first December check closes November with those numbers. */
    @Test
    void theFirstCheckOfANewMonthClosesThePreviousOne() {
        service.setClock(Clock.fixed(Instant.parse("2026-12-01T06:00:00Z"), ZoneId.of("America/Recife")));
        november(200, 180, 12, 12);
        when(siegClient.countXmls(any(), any())).thenReturn(new SiegDocumentCounts(3, 0));
        when(siegClient.downloadXmls(any(), any())).thenReturn(List.of());
        when(goodsInvoices.countDeliveredBy(eq(companyId), eq(false), any(), any(), any(), any())).thenReturn(0L);

        service.syncFromSieg(filter(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1), FiscalXmlSide.RECEIVED));

        CaptureCounts y = counts.of(company);
        CapturePeriodCount receivedPrev = y.previous(CaptureSide.RECEIVED).orElseThrow();
        assertThat(receivedPrev.getPeriodMonth()).isEqualTo(NOVEMBER);
        assertThat(receivedPrev.getProviderTotal()).isEqualTo(200);
        assertThat(receivedPrev.getImportedTotal()).isEqualTo(180);
        // the issued side's November counters are closed at the same time (the window is shared)
        CapturePeriodCount issuedPrev = y.previous(CaptureSide.ISSUED).orElseThrow();
        assertThat(issuedPrev.getPeriodMonth()).isEqualTo(NOVEMBER);
        assertThat(issuedPrev.getProviderTotal()).isEqualTo(12);
        // and the current month starts over
        CapturePeriodCount december = counts.require(companyId, CaptureSide.RECEIVED, DECEMBER);
        assertThat(december.getProviderTotal()).isEqualTo(3);
        assertThat(december.getImportedTotal()).isZero();
    }

    /** CT-CP-67: X, November 40/40, closes 40/40. CT-CP-68: two checks in the same month do not touch prev_*. */
    @Test
    void sameMonthDoesNotCopyAndAManualSyncBackDoesNotCopy() {
        LocalDateTime at = LocalDateTime.of(2026, 12, 1, 3, 0);
        CaptureCounts x = november(40, 40, 1, 1);
        x.closePreviousMonth(LocalDate.of(2026, 12, 1), at);
        assertThat(x.previous(CaptureSide.RECEIVED).orElseThrow().getPeriodMonth()).isEqualTo(NOVEMBER);
        assertThat(x.previous(CaptureSide.RECEIVED).orElseThrow().getImportedTotal()).isEqualTo(40);

        counts.rows().clear();
        CaptureCounts same = november(40, 30, 1, 1);
        same.closePreviousMonth(LocalDate.of(2026, 11, 1), at);
        assertThat(same.previous(CaptureSide.RECEIVED)).isEmpty();

        counts.rows().clear();
        CaptureCounts back = november(40, 30, 1, 1);
        back.closePreviousMonth(LocalDate.of(2026, 9, 1), at);
        assertThat(back.previous(CaptureSide.RECEIVED)).as("a manual sync of an earlier month closes nothing").isEmpty();
    }

    /** November checked on the 30th at 22:00 on both sides (the old counters of the row). */
    private CaptureCounts november(int total, int imported, int issuedTotal, int issuedImported) {
        LocalDateTime checkedAt = LocalDateTime.of(2026, 11, 30, 22, 0);
        counts.seed(company, CaptureSide.RECEIVED, NOVEMBER, total, imported, checkedAt);
        counts.seed(company, CaptureSide.ISSUED, NOVEMBER, issuedTotal, issuedImported, checkedAt);
        return counts.of(company);
    }
}
