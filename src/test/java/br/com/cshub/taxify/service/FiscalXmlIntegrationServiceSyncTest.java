package br.com.cshub.taxify.service;

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
        firm.setSiegApiKey("test-api-key");
        company.setAccountingFirm(firm);
        lenient().when(companyValidation.checkExist(companyId)).thenReturn(company);
        lenient().when(messageService.getMessage(any())).thenReturn("error");
        lenient().when(syncControlRepository.findByCompany_Id(companyId)).thenReturn(Optional.empty());

        SiegProperties properties = new SiegProperties();

        FiscalXmlSyncProperties syncProperties = new FiscalXmlSyncProperties();
        syncProperties.setStoragePath(tempDir.toString());

        service = new FiscalXmlIntegrationService(siegClient, new FiscalXmlMetadataParser(),
                new FiscalXmlFileStorage(syncProperties, messageService), new FiscalXmlPackageExtractor(),
                xmlDocumentRepository, syncControlRepository, companyValidation, messageService, properties);
    }

    @Test
    void skipsBaixarXmlsWhenSiegReportsZeroPendingNfe() {
        when(siegClient.contarXmlsNfe(any(SiegBaixarXmlsRequest.class), any())).thenReturn(0);

        XmlDownloadResultDTO result = service.syncFromSieg(filter());

        verify(siegClient, never()).baixarXmls(any(), any());
        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.xmls()).isEmpty();
    }

    @Test
    void callsBaixarXmlsWhenSiegReportsPendingNfe() {
        when(siegClient.contarXmlsNfe(any(SiegBaixarXmlsRequest.class), any())).thenReturn(3);
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());

        service.syncFromSieg(filter());

        verify(siegClient).baixarXmls(any(), any());
    }

    @Test
    void stillCallsBaixarXmlsWhenTheCountCheckItselfFails() {
        when(siegClient.contarXmlsNfe(any(SiegBaixarXmlsRequest.class), any())).thenThrow(new RuntimeException("boom"));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());

        service.syncFromSieg(filter());

        verify(siegClient).baixarXmls(any(), any());
        verify(syncControlRepository, never()).save(any());
    }

    @Test
    void updatesSyncControlWithSiegTotalAndActualPersistedCountWhenCountSucceeds() {
        when(siegClient.contarXmlsNfe(any(SiegBaixarXmlsRequest.class), any())).thenReturn(200);
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndIssueDateBetween(eq(companyId), any(), any())).thenReturn(100L);

        LocalDate inicio = LocalDate.of(2026, 9, 10);
        LocalDate fim = LocalDate.of(2026, 9, 11);
        service.syncFromSieg(filter(inicio, fim));

        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        FiscalXmlSyncControl control = captor.getValue();
        assertThat(control.getCompany().getId()).isEqualTo(companyId);
        assertThat(control.getDataEmissaoInicio()).isEqualTo(inicio);
        assertThat(control.getDataEmissaoFim()).isEqualTo(fim);
        assertThat(control.getTotalSieg()).isEqualTo(200);
        assertThat(control.getTotalImportado()).isEqualTo(100);
        assertThat(control.getUltimaVerificacaoEm()).isNotNull();
    }

    @Test
    void totalImportadoIsScopedToTheSyncWindowNotTheCompanysLifetimeTotal() {
        when(siegClient.contarXmlsNfe(any(SiegBaixarXmlsRequest.class), any())).thenReturn(30);
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndIssueDateBetween(eq(companyId), any(), any())).thenReturn(30L);
        // Lifetime total across every month ever imported, deliberately much bigger than
        // the window-scoped count above - if updateSyncControl ever regresses back to
        // countByCompany_Id, this makes it obvious via a wrong totalImportado.
        lenient().when(xmlDocumentRepository.countByCompany_Id(companyId)).thenReturn(9999L);

        service.syncFromSieg(filter(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));

        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        assertThat(captor.getValue().getTotalImportado()).isEqualTo(30);
    }

    @Test
    void skipsTheCountCheckAndGoesStraightToDownloadWhenSkipCountCheckIsTrue() {
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndIssueDateBetween(eq(companyId), any(), any())).thenReturn(50L);

        service.syncFromSieg(filter(), true);

        verify(siegClient, never()).contarXmlsNfe(any(), any());
        verify(siegClient).baixarXmls(any(), any());
    }

    @Test
    void skippingTheCountCheckLeavesTheExistingTotalSiegUntouchedButRefreshesTotalImportado() {
        FiscalXmlSyncControl existing = new FiscalXmlSyncControl();
        existing.setId(UUID.randomUUID());
        existing.setTotalSieg(200);
        when(syncControlRepository.findByCompany_Id(companyId)).thenReturn(Optional.of(existing));
        when(siegClient.baixarXmls(any(), any())).thenReturn(List.of());
        when(xmlDocumentRepository.countByCompany_IdAndIssueDateBetween(eq(companyId), any(), any())).thenReturn(120L);

        service.syncFromSieg(filter(), true);

        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        FiscalXmlSyncControl control = captor.getValue();
        assertThat(control.getTotalSieg()).isEqualTo(200);
        assertThat(control.getTotalImportado()).isEqualTo(120);
        assertThat(control.getUltimaVerificacaoEm()).isNotNull();
    }

    @Test
    void reusesTheExistingSyncControlRowInsteadOfCreatingANewOne() {
        FiscalXmlSyncControl existing = new FiscalXmlSyncControl();
        existing.setId(UUID.randomUUID());
        when(syncControlRepository.findByCompany_Id(companyId)).thenReturn(Optional.of(existing));
        when(siegClient.contarXmlsNfe(any(SiegBaixarXmlsRequest.class), any())).thenReturn(0);

        service.syncFromSieg(filter());

        ArgumentCaptor<FiscalXmlSyncControl> captor = ArgumentCaptor.forClass(FiscalXmlSyncControl.class);
        verify(syncControlRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
    }

    private XmlDownloadFilterDTO filter() {
        return filter(null, null);
    }

    private XmlDownloadFilterDTO filter(LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim) {
        return new XmlDownloadFilterDTO(companyId.toString(), 1, dataEmissaoInicio, dataEmissaoFim, null, null, null);
    }
}
