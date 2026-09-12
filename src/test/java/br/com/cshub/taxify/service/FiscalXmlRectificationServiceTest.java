package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.InvoiceAuditLogRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiscalXmlRectificationServiceTest {

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
              <NFe>
                <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                  <det nItem="1">
                    <prod>
                      <cProd>P001</cProd>
                      <xProd>Produto Um</xProd>
                      <NCM>12345678</NCM>
                      <CFOP>5405</CFOP>
                    </prod>
                  </det>
                </infNFe>
              </NFe>
            </nfeProc>
            """;

    @Mock
    private FiscalXmlDocumentRepository documentRepository;
    @Mock
    private ProductFiscalRuleRepository ruleRepository;
    @Mock
    private InvoiceAuditLogRepository auditLogRepository;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private MessageService messageService;
    @Mock
    private TenantAccessService tenantAccessService;

    private FiscalXmlRectificationService service;
    private UUID documentId;
    private UUID companyId;
    private Path originalFile;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        FiscalXmlSyncProperties properties = new FiscalXmlSyncProperties();
        properties.setStoragePath(tempDir.toString());
        FiscalXmlFileStorage fileStorage = new FiscalXmlFileStorage(properties, messageService);
        service = new FiscalXmlRectificationService(documentRepository, ruleRepository, auditLogRepository,
                fileStorage, currentUserService, messageService, tenantAccessService);

        documentId = UUID.randomUUID();
        companyId = UUID.randomUUID();

        originalFile = tempDir.resolve("original.xml");
        Files.writeString(originalFile, XML, StandardCharsets.UTF_8);

        lenient().when(messageService.getMessage(any())).thenReturn("error");
        lenient().when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());
    }

    private FiscalXmlDocument document() {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setId(documentId);
        Company company = new Company(companyId);
        company.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        document.setCompany(company);
        document.setAccessKey("35240112345678000199550010000000011000000010");
        document.setXmlFilePath(originalFile.toString());
        return document;
    }

    @Test
    void throwsWhenDocumentDoesNotExist() {
        when(documentRepository.findFirstByIdAndDeletedIsFalse(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rectify(documentId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void throwsWhenXmlIsNotAnNfe() throws IOException {
        Files.writeString(originalFile, "<somethingElse></somethingElse>", StandardCharsets.UTF_8);
        when(documentRepository.findFirstByIdAndDeletedIsFalse(documentId)).thenReturn(Optional.of(document()));

        assertThatThrownBy(() -> service.rectify(documentId)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void doesNothingWhenNoRuleIsRegisteredForTheProduct() {
        when(documentRepository.findFirstByIdAndDeletedIsFalse(documentId)).thenReturn(Optional.of(document()));
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P001")).thenReturn(Optional.empty());

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.correcoesAplicadas()).isEmpty();
        assertThat(result.rectifiedFilePath()).isNull();
        verify(auditLogRepository, never()).save(any());
        // Still saved once, to record lastRectificationCheckedAt - see
        // FiscalXmlRectificationScheduler, which uses that to skip documents already
        // checked since the company's rules last changed.
        verify(documentRepository).save(any());
    }

    @Test
    void doesNothingWhenTheItemAlreadyMatchesTheRule() {
        when(documentRepository.findFirstByIdAndDeletedIsFalse(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setCodigoProduto("P001");
        rule.setCfopCorreto("5405");
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P001")).thenReturn(Optional.of(rule));

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.correcoesAplicadas()).isEmpty();
        assertThat(result.rectifiedFilePath()).isNull();
        verify(auditLogRepository, never()).save(any());
        verify(documentRepository).save(any());
    }

    @Test
    void writesANewFileWithTheCorrectedCfopAndLeavesTheOriginalUntouched() throws IOException {
        when(documentRepository.findFirstByIdAndDeletedIsFalse(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setCodigoProduto("P001");
        rule.setCfopCorreto("5102");
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P001")).thenReturn(Optional.of(rule));

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.correcoesAplicadas()).hasSize(1);
        assertThat(result.correcoesAplicadas().get(0)).contains("CFOP").contains("5405").contains("5102");
        assertThat(result.rectifiedFilePath()).isNotNull();
        assertThat(result.originalFilePath()).isEqualTo(originalFile.toString());

        String rectifiedContent = Files.readString(Path.of(result.rectifiedFilePath()), StandardCharsets.UTF_8);
        assertThat(rectifiedContent).contains("<CFOP>5102</CFOP>");
        assertThat(rectifiedContent).contains("Retificado por taxify");

        String originalContent = Files.readString(originalFile, StandardCharsets.UTF_8);
        assertThat(originalContent).contains("<CFOP>5405</CFOP>");

        ArgumentCaptor<InvoiceAuditLog> logCaptor = ArgumentCaptor.forClass(InvoiceAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        InvoiceAuditLog savedLog = logCaptor.getValue();
        assertThat(savedLog.getExternalInvoiceKey()).isEqualTo("35240112345678000199550010000000011000000010");
        assertThat(savedLog.getStatus()).isEqualTo(InvoiceAuditStatus.RECTIFIED);
        assertThat(savedLog.getCompany().getId()).isEqualTo(companyId);
        assertThat(savedLog.getNotes()).contains("CFOP").contains("5405").contains("5102");
        assertThat(savedLog.getUser()).isNull();

        ArgumentCaptor<FiscalXmlDocument> documentCaptor = ArgumentCaptor.forClass(FiscalXmlDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        assertThat(documentCaptor.getValue().getRectifiedFilePath()).isEqualTo(result.rectifiedFilePath());
    }

    @Test
    void attachesTheAuthenticatedUserToTheAuditLogWhenThereIsOne() {
        when(documentRepository.findFirstByIdAndDeletedIsFalse(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setCodigoProduto("P001");
        rule.setCfopCorreto("5102");
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P001")).thenReturn(Optional.of(rule));
        User currentUser = new User();
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(currentUser));

        service.rectify(documentId);

        ArgumentCaptor<InvoiceAuditLog> logCaptor = ArgumentCaptor.forClass(InvoiceAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getUser()).isSameAs(currentUser);
    }

    @Test
    void rerunningWithTheSameRuleDoesNotWriteAnotherFileOrLog() {
        when(documentRepository.findFirstByIdAndDeletedIsFalse(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setCodigoProduto("P001");
        rule.setCfopCorreto("5102");
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P001")).thenReturn(Optional.of(rule));

        FiscalXmlRectificationResultDTO first = service.rectify(documentId);
        assertThat(first.rectifiedFilePath()).isNotNull();

        ArgumentCaptor<InvoiceAuditLog> logCaptor = ArgumentCaptor.forClass(InvoiceAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        String externalInvoiceKey = logCaptor.getValue().getExternalInvoiceKey();
        String notes = logCaptor.getValue().getNotes();
        when(auditLogRepository.findFirstByExternalInvoiceKeyAndStatusAndDeletedIsFalseOrderByCreatedDateDesc(
                externalInvoiceKey, InvoiceAuditStatus.RECTIFIED)).thenReturn(Optional.of(logCaptor.getValue()));

        // Simulates a scheduler firing again over the same (still-unmodified) original
        // file - it would still find the same "divergence" every time otherwise.
        FiscalXmlRectificationResultDTO second = service.rectify(documentId);

        assertThat(second.correcoesAplicadas()).hasSize(1);
        assertThat(second.rectifiedFilePath()).isNull();
        verify(auditLogRepository, times(1)).save(any());
        // Once per rectify() call (two calls here) - it's the log/rectified-file writing
        // that's skipped on the second call, not the lastRectificationCheckedAt bookkeeping.
        verify(documentRepository, times(2)).save(any());
    }

    @Test
    void alsoCorrectsNcmWhenTheRulePinsOne() throws IOException {
        when(documentRepository.findFirstByIdAndDeletedIsFalse(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setCodigoProduto("P001");
        rule.setCfopCorreto("5405");
        rule.setNcmCorreto("87654321");
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P001")).thenReturn(Optional.of(rule));

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.correcoesAplicadas()).hasSize(1);
        String rectifiedContent = Files.readString(Path.of(result.rectifiedFilePath()), StandardCharsets.UTF_8);
        assertThat(rectifiedContent).contains("<NCM>87654321</NCM>");
    }
}
