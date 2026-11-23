package br.com.cshub.taxify.service;

import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventParser;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.integration.sieg.SiegProperties;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiegXmlIntegrationServiceImportTest {

    private static final String NFE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
              <NFe>
                <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                  <ide><dhEmi>2026-09-01T10:15:00-03:00</dhEmi></ide>
                  <emit><CNPJ>12345678000199</CNPJ></emit>
                  <dest><CNPJ>98765432000188</CNPJ></dest>
                </infNFe>
              </NFe>
            </nfeProc>
            """;

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
    @Mock
    private br.com.cshub.taxify.repository.SefazNoteSummaryRepository summaryRepository;
    @Mock
    private ServiceInvoiceService serviceInvoiceService;

    private FiscalXmlIntegrationService service;
    private UUID companyId;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        Company company = new Company(companyId);
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        lenient().when(companyValidation.checkExist(companyId)).thenReturn(company);
        lenient().when(messageService.getMessage(any())).thenReturn("error");

        SiegProperties properties = new SiegProperties();

        FiscalXmlSyncProperties syncProperties = new FiscalXmlSyncProperties();
        syncProperties.setStoragePath(tempDir.toString());

        service = new FiscalXmlIntegrationService(siegClient, new FiscalXmlMetadataParser(),
                new FiscalXmlFileStorage(syncProperties, messageService), new FiscalXmlPackageExtractor(),
                xmlDocumentRepository, syncControlRepository, companyValidation, messageService, properties, firmIntegrationService,
                new FiscalEventParser(), eventService, summaryRepository, serviceInvoiceService);
        lenient().when(xmlDocumentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void importsASingleXmlFile() {
        XmlDownloadResultDTO result = service.importManual(companyId.toString(), NFE_XML.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isEqualTo(1);
        assertThat(result.totalDuplicatedSkipped()).isZero();
        // the upload answers with counts and keys - the XMLs themselves stay out of the response
        assertThat(result.xmls()).isEmpty();
        assertThat(result.persistedAccessKeys()).containsExactly("35240112345678000199550010000000011000000010");
    }

    @Test
    void importsEveryXmlInsideAZip() throws Exception {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            zos.putNextEntry(new ZipEntry("one.xml"));
            zos.write(NFE_XML.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            String secondXml = NFE_XML.replace("35240112345678000199550010000000011000000010", "35240112345678000199550010000000021000000020");
            zos.putNextEntry(new ZipEntry("two.xml"));
            zos.write(secondXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), zipBytes.toByteArray());

        assertThat(result.totalPersistedNew()).isEqualTo(2);
        assertThat(result.persistedAccessKeys()).containsExactlyInAnyOrder(
                "35240112345678000199550010000000011000000010", "35240112345678000199550010000000021000000020");
    }

    @Test
    void eventXmlsGoToTheEventServiceAndAreNotCountedAsNotesOrErrors() {
        String cancellation = br.com.cshub.taxify.integration.fiscalxml.FiscalEventParserTest.procEvento(
                "110111", "<descEvento>Cancelamento</descEvento><xJust>Erro</xJust>", "135");
        when(eventService.record(any(), any())).thenReturn(true);

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), cancellation.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalEventsRecorded()).isEqualTo(1);
        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalParseErrors()).isZero();
        verify(eventService).record(any(), org.mockito.ArgumentMatchers.<FiscalEventData>argThat(FiscalEventData::isCancellation));
    }

    @Test
    void skipsAnAlreadyPersistedAccessKeyAsDuplicate() {
        when(xmlDocumentRepository.existsByCompany_IdAndAccessKey(companyId, "35240112345678000199550010000000011000000010")).thenReturn(true);

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), NFE_XML.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalDuplicatedSkipped()).isEqualTo(1);
    }

    @Test
    void anUploadedNoteKeepsItsOriginAndIsNotCountedForEitherSource() {
        service.importManual(companyId.toString(), NFE_XML.getBytes(StandardCharsets.UTF_8));

        org.mockito.ArgumentCaptor<br.com.cshub.taxify.domain.xml.FiscalXmlDocument> saved = org.mockito.ArgumentCaptor.captor();
        verify(xmlDocumentRepository).save(saved.capture());
        assertThat(saved.getValue().getSourceSystem()).isEqualTo("UPLOAD");
        assertThat(saved.getValue().getFoundInSiegAt()).isNull();
        assertThat(saved.getValue().getFoundInSefazAt()).isNull();
    }

    @Test
    void aSefazNoteCompletesItsSummaryAndCountsAsSeenBySefaz() {
        br.com.cshub.taxify.domain.xml.SefazNoteSummary summary = new br.com.cshub.taxify.domain.xml.SefazNoteSummary();
        when(summaryRepository.findByCompany_IdAndAccessKey(companyId, "35240112345678000199550010000000011000000010"))
                .thenReturn(java.util.Optional.of(summary));

        service.importFromSefaz(companyId, java.util.List.of(NFE_XML.getBytes(StandardCharsets.UTF_8)));

        org.mockito.ArgumentCaptor<br.com.cshub.taxify.domain.xml.FiscalXmlDocument> saved = org.mockito.ArgumentCaptor.captor();
        verify(xmlDocumentRepository).save(saved.capture());
        assertThat(saved.getValue().getSourceSystem()).isEqualTo("SEFAZ_DIST");
        assertThat(saved.getValue().getFoundInSefazAt()).isNotNull();
        assertThat(summary.isCompleted()).isTrue();
    }

    @Test
    void aNoteAlreadyStoredIsMarkedAsFoundByTheNewSource() {
        br.com.cshub.taxify.domain.xml.FiscalXmlDocument known = new br.com.cshub.taxify.domain.xml.FiscalXmlDocument();
        String key = "35240112345678000199550010000000011000000010";
        when(xmlDocumentRepository.existsByCompany_IdAndAccessKey(companyId, key)).thenReturn(true);
        when(xmlDocumentRepository.findFirstByCompany_IdAndAccessKey(companyId, key)).thenReturn(java.util.Optional.of(known));

        XmlDownloadResultDTO result = service.importFromSefaz(companyId, java.util.List.of(NFE_XML.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.totalDuplicatedSkipped()).isEqualTo(1);
        assertThat(known.getFoundInSefazAt()).isNotNull();
        assertThat(known.getFoundInSiegAt()).isNull();
        verify(xmlDocumentRepository).save(known);
    }

    @Test
    void throwsWhenNoFileIsUploaded() {
        assertThatThrownBy(() -> service.importManual(companyId.toString(), new byte[0]))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void countsGarbageContentAsAParseErrorInsteadOfFailing() {
        XmlDownloadResultDTO result = service.importManual(companyId.toString(), "not xml at all".getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalParseErrors()).isEqualTo(1);
        assertThat(result.totalPersistedNew()).isEqualTo(1);
    }

    @Test
    void aBigZipIsStoredInBatchesAndTheCountsAddUp() throws Exception {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            for (int i = 0; i < 250; i++) {
                String key = String.format("352401123456780001995500100000%04d1000000010", i);
                zos.putNextEntry(new ZipEntry("nota-" + i + ".xml"));
                zos.write(NFE_XML.replace("35240112345678000199550010000000011000000010", key).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), new java.io.ByteArrayInputStream(zipBytes.toByteArray()));

        // 3 batches (100 + 100 + 50) summed into one answer
        assertThat(result.totalXmlsCounted()).isEqualTo(250);
        assertThat(result.totalPersistedNew()).isEqualTo(250);
        assertThat(result.persistedAccessKeys()).hasSize(250).doesNotHaveDuplicates();
        assertThat(result.xmls()).isEmpty();
    }
}
