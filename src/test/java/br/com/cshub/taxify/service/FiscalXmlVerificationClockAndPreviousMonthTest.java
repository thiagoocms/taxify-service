package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
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
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-CP-1 (CP-RN-21, CT-CP-64): the verification times in Recife even with the JVM's clock elsewhere; B-CP-6 (CP-RN-22,
 * CT-CP-66 a 68): the previous month's counters kept on the turn of the month.
 */
class FiscalXmlVerificationClockAndPreviousMonthTest {

    @TempDir
    Path tempDir;

    private SiegClient siegClient;
    private FiscalXmlDocumentRepository documents;
    private FiscalXmlSyncControlRepository controls;
    private FiscalXmlIntegrationService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        siegClient = mock(SiegClient.class);
        documents = mock(FiscalXmlDocumentRepository.class);
        controls = mock(FiscalXmlSyncControlRepository.class);
        CompanyValidation validation = mock(CompanyValidation.class);
        MessageService messages = mock(MessageService.class);
        FirmIntegrationService integrations = mock(FirmIntegrationService.class);
        companyId = UUID.randomUUID();
        Company company = new Company(companyId);
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
        company.setAccountingFirm(firm);
        when(validation.checkExist(companyId)).thenReturn(company);
        when(integrations.getApiKey(firm.getId(), IntegrationType.SIEG)).thenReturn("chave-ficticia");
        FiscalXmlSyncProperties syncProperties = new FiscalXmlSyncProperties();
        syncProperties.setStoragePath(tempDir.toString());
        service = new FiscalXmlIntegrationService(siegClient, new FiscalXmlMetadataParser(),
                new FiscalXmlFileStorage(syncProperties, messages), new FiscalXmlPackageExtractor(), documents, controls, validation,
                messages, new SiegProperties(), integrations, new FiscalEventParser(), mock(FiscalXmlEventService.class),
                mock(br.com.cshub.taxify.repository.SefazNoteSummaryRepository.class), mock(ServiceInvoiceService.class));
    }

    private XmlDownloadFilterDTO filter(LocalDate from, LocalDate to, FiscalXmlSide side) {
        return new XmlDownloadFilterDTO(companyId.toString(), 1, from, to, 50, 0, null, side);
    }

    /** CT-CP-64: 13:00 UTC = 10:00 in Recife - the verification is stored as 10:00, not 13:00. */
    @Test
    void verificationTimeIsRecifeEvenWithTheJvmInUtc() {
        service.setClock(Clock.fixed(Instant.parse("2026-11-27T13:00:00Z"), ZoneId.of("America/Recife")));
        when(controls.findByCompany_Id(companyId)).thenReturn(Optional.empty());
        when(siegClient.contarXmls(any(), any())).thenReturn(new SiegDocumentCounts(0, 0));

        service.syncFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), FiscalXmlSide.ISSUED));
        service.syncFromSieg(filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27), FiscalXmlSide.RECEIVED));

        ArgumentCaptor<FiscalXmlSyncControl> saved = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(controls, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getIssuedCheckedAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0));
        assertThat(saved.getAllValues().get(1).getLastCheckedAt()).isEqualTo(LocalDateTime.of(2026, 11, 27, 10, 0));
    }

    /** CT-CP-66 / CA-CP-26: Y, November 180 of 200; the first December check closes November with those numbers. */
    @Test
    void theFirstCheckOfANewMonthClosesThePreviousOne() {
        service.setClock(Clock.fixed(Instant.parse("2026-12-01T06:00:00Z"), ZoneId.of("America/Recife")));
        FiscalXmlSyncControl y = novemberControl(200, 180, 12, 12);
        when(controls.findByCompany_Id(companyId)).thenReturn(Optional.of(y));
        when(siegClient.contarXmls(any(), any())).thenReturn(new SiegDocumentCounts(3, 0));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(documents.countByCompany_IdAndOwnIssueAndIssueDateBetweenAndFoundInSiegAtIsNotNull(eq(companyId), eq(false), any(), any())).thenReturn(0L);

        service.syncFromSieg(filter(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 1), FiscalXmlSide.RECEIVED));

        assertThat(y.getReceivedPrevMonth()).isEqualTo("2026-11");
        assertThat(y.getReceivedPrevTotalSieg()).isEqualTo(200);
        assertThat(y.getReceivedPrevImported()).isEqualTo(180);
        // the issued side's November counters are closed at the same time (the window is shared)
        assertThat(y.getIssuedPrevMonth()).isEqualTo("2026-11");
        assertThat(y.getIssuedPrevTotalSieg()).isEqualTo(12);
        // and the current month starts over
        assertThat(y.getTotalSieg()).isEqualTo(3);
        assertThat(y.getTotalImported()).isZero();
    }

    /** CT-CP-67: X, November 40/40, closes 40/40. CT-CP-68: two checks in the same month do not touch prev_*. */
    @Test
    void sameMonthDoesNotCopyAndAManualSyncBackDoesNotCopy() {
        FiscalXmlSyncControl x = novemberControl(40, 40, 1, 1);
        FiscalXmlIntegrationService.closePreviousMonth(x, LocalDate.of(2026, 12, 1));
        assertThat(x.getReceivedPrevMonth()).isEqualTo("2026-11");
        assertThat(x.getReceivedPrevImported()).isEqualTo(40);

        FiscalXmlSyncControl same = novemberControl(40, 30, 1, 1);
        FiscalXmlIntegrationService.closePreviousMonth(same, LocalDate.of(2026, 11, 1));
        assertThat(same.getReceivedPrevMonth()).isNull();

        FiscalXmlSyncControl back = novemberControl(40, 30, 1, 1);
        FiscalXmlIntegrationService.closePreviousMonth(back, LocalDate.of(2026, 9, 1));
        assertThat(back.getReceivedPrevMonth()).as("a manual sync of an earlier month closes nothing").isNull();
    }

    private static FiscalXmlSyncControl novemberControl(int total, int imported, int issuedTotal, int issuedImported) {
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setIssueDateFrom(LocalDate.of(2026, 11, 1));
        control.setIssueDateTo(LocalDate.of(2026, 11, 30));
        control.setTotalSieg(total);
        control.setTotalImported(imported);
        control.setLastCheckedAt(LocalDateTime.of(2026, 11, 30, 22, 0));
        control.setIssuedTotalSieg(issuedTotal);
        control.setIssuedTotalImported(issuedImported);
        control.setIssuedCheckedAt(LocalDateTime.of(2026, 11, 30, 22, 0));
        return control;
    }
}
