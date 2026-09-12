package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlItemDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.integration.sieg.SiegProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiscalXmlIntegrationServiceExportTest {

    private static final String ORIGINAL_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
              <NFe>
                <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                  <det nItem="1">
                    <prod>
                      <cProd>P001</cProd>
                      <xProd>Produto Original</xProd>
                      <NCM>12345678</NCM>
                      <CFOP>5405</CFOP>
                    </prod>
                  </det>
                </infNFe>
              </NFe>
            </nfeProc>
            """;

    private static final String RECTIFIED_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
              <NFe>
                <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                  <det nItem="1">
                    <prod>
                      <cProd>P001</cProd>
                      <xProd>Produto Original</xProd>
                      <NCM>12345678</NCM>
                      <CFOP>5102</CFOP>
                    </prod>
                  </det>
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

        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setStoragePath(tempDir.toString());

        service = new FiscalXmlIntegrationService(siegClient, new FiscalXmlMetadataParser(),
                new FiscalXmlFileStorage(properties, messageService), new FiscalXmlPackageExtractor(),
                xmlDocumentRepository, syncControlRepository, companyValidation, messageService, new SiegProperties());
    }

    @Test
    void exportsTheOriginalXmlWhenTheNoteWasNeverRectified() throws IOException {
        Path originalFile = tempDir.resolve("original.xml");
        Files.writeString(originalFile, ORIGINAL_XML, StandardCharsets.UTF_8);

        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setXmlFilePath(originalFile.toString());
        when(xmlDocumentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(any(), any(), any()))
                .thenReturn(List.of(document));

        List<FiscalXmlItemDTO> result = service.exportXmls(filter());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).xmlContent()).contains("<CFOP>5405</CFOP>");
    }

    @Test
    void exportsTheRectifiedXmlInsteadOfTheOriginalWhenOneExists() throws IOException {
        Path originalFile = tempDir.resolve("original.xml");
        Files.writeString(originalFile, ORIGINAL_XML, StandardCharsets.UTF_8);
        Path rectifiedFile = tempDir.resolve("original-retificado.xml");
        Files.writeString(rectifiedFile, RECTIFIED_XML, StandardCharsets.UTF_8);

        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setXmlFilePath(originalFile.toString());
        document.setRectifiedFilePath(rectifiedFile.toString());
        when(xmlDocumentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(any(), any(), any()))
                .thenReturn(List.of(document));

        List<FiscalXmlItemDTO> result = service.exportXmls(filter());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).xmlContent()).contains("<CFOP>5102</CFOP>");
        assertThat(result.get(0).xmlContent()).doesNotContain("<CFOP>5405</CFOP>");
    }

    @Test
    void skipsADocumentWhoseFileCannotBeReadInsteadOfFailingTheWholeExport() {
        FiscalXmlDocument missingFile = new FiscalXmlDocument();
        missingFile.setXmlFilePath(tempDir.resolve("does-not-exist.xml").toString());
        when(xmlDocumentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(any(), any(), any()))
                .thenReturn(List.of(missingFile));

        List<FiscalXmlItemDTO> result = service.exportXmls(filter());

        assertThat(result).isEmpty();
    }

    @Test
    void bundlesEachNoteAsAZipEntryNamedByAccessKeyUsingTheRectifiedVersionWhenPresent() throws IOException {
        Path originalFile = tempDir.resolve("original.xml");
        Files.writeString(originalFile, ORIGINAL_XML, StandardCharsets.UTF_8);
        Path rectifiedFile = tempDir.resolve("original-retificado.xml");
        Files.writeString(rectifiedFile, RECTIFIED_XML, StandardCharsets.UTF_8);

        FiscalXmlDocument rectified = new FiscalXmlDocument();
        rectified.setId(UUID.randomUUID());
        rectified.setAccessKey("35240112345678000199550010000000011000000010");
        rectified.setXmlFilePath(originalFile.toString());
        rectified.setRectifiedFilePath(rectifiedFile.toString());

        FiscalXmlDocument neverRectified = new FiscalXmlDocument();
        neverRectified.setId(UUID.randomUUID());
        neverRectified.setAccessKey("99999912345678000199550010000000011000000099");
        neverRectified.setXmlFilePath(originalFile.toString());

        when(xmlDocumentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(any(), any(), any()))
                .thenReturn(List.of(rectified, neverRectified));

        byte[] zipBytes = service.exportXmlsZip(filter());

        Map<String, String> entries = readZipEntries(zipBytes);
        assertThat(entries).containsOnlyKeys(
                "35240112345678000199550010000000011000000010.xml",
                "99999912345678000199550010000000011000000099.xml");
        assertThat(entries.get("35240112345678000199550010000000011000000010.xml")).contains("<CFOP>5102</CFOP>");
        assertThat(entries.get("99999912345678000199550010000000011000000099.xml")).contains("<CFOP>5405</CFOP>");
    }

    @Test
    void fallsBackToDocumentIdWhenThereIsNoAccessKeyAndSkipsUnreadableFiles() throws IOException {
        Path originalFile = tempDir.resolve("original.xml");
        Files.writeString(originalFile, ORIGINAL_XML, StandardCharsets.UTF_8);

        FiscalXmlDocument noAccessKey = new FiscalXmlDocument();
        noAccessKey.setId(UUID.randomUUID());
        noAccessKey.setXmlFilePath(originalFile.toString());

        FiscalXmlDocument missingFile = new FiscalXmlDocument();
        missingFile.setId(UUID.randomUUID());
        missingFile.setXmlFilePath(tempDir.resolve("does-not-exist.xml").toString());

        when(xmlDocumentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(any(), any(), any()))
                .thenReturn(List.of(noAccessKey, missingFile));

        byte[] zipBytes = service.exportXmlsZip(filter());

        Map<String, String> entries = readZipEntries(zipBytes);
        assertThat(entries).containsOnlyKeys(noAccessKey.getId() + ".xml");
    }

    private Map<String, String> readZipEntries(byte[] zipBytes) throws IOException {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private XmlDownloadFilterDTO filter() {
        return new XmlDownloadFilterDTO(companyId.toString(), 1, null, null, null, null, null);
    }
}
