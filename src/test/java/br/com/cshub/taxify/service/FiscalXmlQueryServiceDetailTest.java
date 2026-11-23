package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlHeaderExtras;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlDetailDTO;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Opening a note: built from the columns (no storage read) once the parser stores every header field. */
@ExtendWith(MockitoExtension.class)
class FiscalXmlQueryServiceDetailTest {

    private static final String XML = """
            <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe"><NFe>
              <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                <ide><cUF>35</cUF><natOp>Venda</natOp><mod>55</mod><serie>1</serie><nNF>1</nNF>
                  <dhEmi>2026-09-01T10:00:00-03:00</dhEmi><tpNF>1</tpNF><tpImp>1</tpImp><tpEmis>9</tpEmis>
                  <cDV>0</cDV><tpAmb>1</tpAmb><procEmi>0</procEmi><verProc>ERP 2.0</verProc></ide>
                <emit><CNPJ>12345678000199</CNPJ><xNome>Emitente</xNome>
                  <enderEmit><cPais>1058</cPais><xPais>BRASIL</xPais></enderEmit></emit>
              </infNFe></NFe></nfeProc>
            """;

    @Mock
    private FiscalXmlDocumentRepository documentRepository;
    @Mock
    private FiscalXmlDocumentItemRepository itemRepository;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private TenantAccessService tenantAccessService;
    @Mock
    private MessageService messageService;
    @Mock
    private FiscalXmlEventService eventService;
    @Mock
    private FiscalXmlFileStorage fileStorage;

    private FiscalXmlQueryService service;
    private FiscalXmlDocument document;

    @BeforeEach
    void setUp() {
        service = new FiscalXmlQueryService(documentRepository, itemRepository, new FiscalXmlMetadataParser(), companyValidation,
                tenantAccessService, messageService, eventService, fileStorage);
        document = new FiscalXmlDocument();
        document.setId(UUID.randomUUID());
        document.setCompany(new Company(UUID.randomUUID()));
        document.setDocumentType("NFE");
        document.setXmlFilePath("s3://bucket/fiscal-xml/note.xml");
        when(documentRepository.findFirstByIdAndDeletedIsFalse(document.getId())).thenReturn(Optional.of(document));
    }

    @Test
    void buildsTheDetailFromTheColumnsWithoutReadingTheFile() throws IOException {
        document.setMetadataVersion(4);
        document.setHeaderExtras(new FiscalXmlHeaderExtras("1", "9", "0", "0", "ERP 2.0", "1058", "BRASIL", null, null));

        FiscalXmlDetailDTO detail = service.findDetail(document.getId());

        verify(fileStorage, never()).read(any());
        assertThat(detail.identificacao().tipoEmissao()).isEqualTo("9");
        assertThat(detail.identificacao().versaoProcessoEmissao()).isEqualTo("ERP 2.0");
        assertThat(detail.emitente().endereco().pais()).isEqualTo("BRASIL");
    }

    @Test
    void readsTheFileForNotesSavedByAnOlderParser() throws IOException {
        document.setMetadataVersion(3);
        when(fileStorage.read(document.getXmlFilePath())).thenReturn(XML.getBytes(StandardCharsets.UTF_8));

        FiscalXmlDetailDTO detail = service.findDetail(document.getId());

        verify(fileStorage).read(document.getXmlFilePath());
        assertThat(detail.identificacao().tipoEmissao()).isEqualTo("9");
    }
}
