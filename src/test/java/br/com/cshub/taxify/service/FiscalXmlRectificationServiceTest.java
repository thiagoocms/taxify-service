package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.fiscaldocument.Cte;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.enumerated.InvoiceAuditStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
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
import java.util.List;
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
    private FiscalDocumentRepository documentRepository;
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
                fileStorage, messageService, tenantAccessService, new InvoiceAuditTrail(auditLogRepository, currentUserService));

        documentId = UUID.randomUUID();
        companyId = UUID.randomUUID();

        originalFile = tempDir.resolve("original.xml");
        Files.writeString(originalFile, XML, StandardCharsets.UTF_8);

        lenient().when(messageService.getMessage(any())).thenReturn("error");
        lenient().when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());
    }

    private Company company() {
        Company company = new Company(companyId);
        company.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        return company;
    }

    private GoodsInvoice document() {
        return TestNotes.note(company()).id(documentId).key("35240112345678000199550010000000011000000010")
                .xml(originalFile.toString()).metadataVersion(null).build();
    }

    @Test
    void throwsWhenDocumentDoesNotExist() {
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rectify(documentId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void throwsWhenXmlIsNotAnNfe() throws IOException {
        Files.writeString(originalFile, "<somethingElse></somethingElse>", StandardCharsets.UTF_8);
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document()));

        assertThatThrownBy(() -> service.rectify(documentId)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void doesNothingWhenNoRuleIsRegisteredForTheProduct() {
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document()));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());

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
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode("P001");
        rule.setCorrectCfop("5405");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.correcoesAplicadas()).isEmpty();
        assertThat(result.rectifiedFilePath()).isNull();
        verify(auditLogRepository, never()).save(any());
        verify(documentRepository).save(any());
    }

    @Test
    void writesANewFileWithTheCorrectedCfopAndLeavesTheOriginalUntouched() throws IOException {
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode("P001");
        rule.setCorrectCfop("5102");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

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

        ArgumentCaptor<FiscalDocument> documentCaptor = ArgumentCaptor.forClass(FiscalDocument.class);
        verify(documentRepository).save(documentCaptor.capture());
        assertThat(((GoodsInvoice) documentCaptor.getValue()).getRectifiedFilePath()).isEqualTo(result.rectifiedFilePath());
    }

    @Test
    void attachesTheAuthenticatedUserToTheAuditLogWhenThereIsOne() {
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode("P001");
        rule.setCorrectCfop("5102");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));
        User currentUser = new User();
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(currentUser));

        service.rectify(documentId);

        ArgumentCaptor<InvoiceAuditLog> logCaptor = ArgumentCaptor.forClass(InvoiceAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getUser()).isSameAs(currentUser);
    }

    @Test
    void rerunningWithTheSameRuleDoesNotWriteAnotherFileOrLog() {
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode("P001");
        rule.setCorrectCfop("5102");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

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
    void removingTheRuleSendsTheNoteBackToTheOriginalXml() {
        GoodsInvoice document = document();
        document.setRectifiedFilePath("rectified/old.xml");
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.correcoesAplicadas()).isEmpty();
        assertThat(document.getRectifiedFilePath()).isNull();
        ArgumentCaptor<InvoiceAuditLog> logCaptor = ArgumentCaptor.forClass(InvoiceAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo(InvoiceAuditStatus.RECTIFICATION_REVERTED);
        assertThat(logCaptor.getValue().getNotes()).contains("XML original");
    }

    @Test
    void addingTheSameRuleBackAfterARevertRectifiesAgain() {
        GoodsInvoice document = document();
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode("P001");
        rule.setCorrectCfop("5102");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));
        InvoiceAuditLog previous = new InvoiceAuditLog();
        previous.setNotes("Retificacao automatica: Item P001: CFOP 5405 -> 5102");
        when(auditLogRepository.findFirstByExternalInvoiceKeyAndStatusAndDeletedIsFalseOrderByCreatedDateDesc(
                document.getAccessKey(), InvoiceAuditStatus.RECTIFIED)).thenReturn(Optional.of(previous));

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.rectifiedFilePath()).isNotNull();
        assertThat(document.getRectifiedFilePath()).isEqualTo(result.rectifiedFilePath());
    }

    @Test
    void alsoCorrectsNcmWhenTheRulePinsOne() throws IOException {
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document()));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode("P001");
        rule.setCorrectCfop("5405");
        rule.setCorrectNcm("87654321");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.correcoesAplicadas()).hasSize(1);
        String rectifiedContent = Files.readString(Path.of(result.rectifiedFilePath()), StandardCharsets.UTF_8);
        assertThat(rectifiedContent).contains("<NCM>87654321</NCM>");
    }

    /** FD-RN-57: the item's effective CFOP is the rectified XML's while the rectification is in effect, its own after it. */
    @Test
    void theEffectiveCfopFollowsTheRectificationAndGoesBackWithTheRevert() throws IOException {
        GoodsInvoice document = parsedDocument();
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(ruleWithCfop("5102")));
        assertThat(document.getItems().getFirst().getEffectiveCfop()).as("on import = the item's CFOP").isEqualTo("5405");

        FiscalXmlRectificationResultDTO rectified = service.rectify(documentId);

        assertThat(rectified.rectifiedFilePath()).isNotNull();
        assertThat(Files.readString(Path.of(rectified.rectifiedFilePath()), StandardCharsets.UTF_8)).contains("<CFOP>5102</CFOP>");
        assertThat(document.getItems().getFirst().getEffectiveCfop()).isEqualTo("5102");
        assertThat(document.getItems().getFirst().getProduct().cfop()).as("the original stays").isEqualTo("5405");

        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());
        service.rectify(documentId);

        assertThat(document.getRectifiedFilePath()).isNull();
        assertThat(document.getItems().getFirst().getEffectiveCfop()).isEqualTo("5405");
    }

    /** A note saved by the current parser, with its one item stored as columns (CFOP 5405, NCM 12345678). */
    private GoodsInvoice parsedDocument() {
        return TestNotes.note(company()).id(documentId).key("35240112345678000199550010000000011000000010")
                .xml(originalFile.toString()).metadataVersion(4)
                .item(TestNotes.item(1).code("P001").cfop("5405").ncm("12345678")).build();
    }

    private ProductFiscalRule ruleWithCfop(String cfop) {
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode("P001");
        rule.setCorrectCfop(cfop);
        return rule;
    }

    @Test
    void doesNotReadTheFileWhenTheStoredItemsAlreadyMatchTheRules() throws IOException {
        Files.delete(originalFile); // reading it would fail the rectification
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(parsedDocument()));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(ruleWithCfop("5405")));

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.correcoesAplicadas()).isEmpty();
        verify(documentRepository).save(any());
    }

    @Test
    void revertsWithoutReadingTheFileWhenTheRuleNoLongerDiverges() throws IOException {
        Files.delete(originalFile);
        GoodsInvoice document = parsedDocument();
        document.setRectifiedFilePath(tempDir.resolve("old-rectified.xml").toString());
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of());

        service.rectify(documentId);

        assertThat(document.getRectifiedFilePath()).isNull();
    }

    @Test
    void readsTheFileWhenAStoredItemDiffersFromItsRule() {
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(parsedDocument()));
        ProductFiscalRule rule = ruleWithCfop("5102");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

        FiscalXmlRectificationResultDTO result = service.rectify(documentId);

        assertThat(result.correcoesAplicadas()).hasSize(1);
        assertThat(result.rectifiedFilePath()).isNotNull();
    }

    @Test
    void rejectsAnotherDocumentTypeWithoutReadingTheFile() throws IOException {
        Files.delete(originalFile);
        Cte document = TestNotes.takenCte(company());
        document.setId(documentId);
        when(documentRepository.findXmlDocument(documentId)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.rectify(documentId)).isInstanceOf(BadRequestException.class);
    }
}
