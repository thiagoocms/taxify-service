package br.com.cshub.taxify.service;

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
        properties.setXmlStoragePath(tempDir.toString());

        FiscalXmlSyncProperties syncProperties = new FiscalXmlSyncProperties();
        syncProperties.setStoragePath(tempDir.toString());

        service = new FiscalXmlIntegrationService(siegClient, new FiscalXmlMetadataParser(),
                new FiscalXmlFileStorage(syncProperties, messageService), new FiscalXmlPackageExtractor(),
                xmlDocumentRepository, syncControlRepository, companyValidation, messageService, properties);
    }

    @Test
    void importsASingleXmlFile() {
        XmlDownloadResultDTO result = service.importManual(companyId.toString(), NFE_XML.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isEqualTo(1);
        assertThat(result.totalDuplicatedSkipped()).isZero();
        assertThat(result.xmls()).hasSize(1);
        assertThat(result.xmls().get(0).accessKey()).isEqualTo("35240112345678000199550010000000011000000010");
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
    void skipsAnAlreadyPersistedAccessKeyAsDuplicate() {
        when(xmlDocumentRepository.existsByAccessKey("35240112345678000199550010000000011000000010")).thenReturn(true);

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), NFE_XML.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalDuplicatedSkipped()).isEqualTo(1);
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
}
