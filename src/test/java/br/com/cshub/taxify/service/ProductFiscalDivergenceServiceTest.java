package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalDivergenceDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckEngine;
import br.com.cshub.taxify.service.fiscalcheck.ProductRuleCheck;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductFiscalDivergenceServiceTest {

    @Mock
    private FiscalXmlDocumentItemRepository itemRepository;
    @Mock
    private ProductFiscalRuleRepository ruleRepository;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private MessageService messageService;

    private ProductFiscalDivergenceService service;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        FiscalCheckEngine engine = new FiscalCheckEngine(List.of(new ProductRuleCheck()), List.of(), ruleRepository,
                org.mockito.Mockito.mock(br.com.cshub.taxify.repository.IbsCbsRateRepository.class));
        service = new ProductFiscalDivergenceService(itemRepository, engine, companyValidation, messageService);
        lenient().when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
    }

    @Test
    void throwsWhenCompanyIdIsNotAValidUuid() {
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");

        assertThatThrownBy(() -> service.findDivergences("not-a-uuid", null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("bad request");
    }

    @Test
    void throwsWhenCompanyDoesNotExist() {
        UUID missingCompanyId = UUID.randomUUID();
        when(companyValidation.checkExist(missingCompanyId))
                .thenThrow(new ResourceNotFoundException("company not found"));

        assertThatThrownBy(() -> service.findDivergences(missingCompanyId.toString(), null, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("company not found");
    }

    @Test
    void flagsAnItemWhoseCfopDoesNotMatchTheRegisteredRule() {
        FiscalXmlDocumentItem item = itemWith("P001", "5405", "12345678");
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(item));

        ProductFiscalRule rule = ruleWith("P001", "5102", null);
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

        List<ProductFiscalDivergenceDTO> result = service.findDivergences(companyId.toString(), null, null);

        assertThat(result).hasSize(1);
        ProductFiscalDivergenceDTO divergence = result.get(0);
        assertThat(divergence.codigoProduto()).isEqualTo("P001");
        assertThat(divergence.cfopAtual()).isEqualTo("5405");
        assertThat(divergence.cfopCorreto()).isEqualTo("5102");
    }

    @Test
    void flagsAnItemWhoseNcmDivergesWhenTheRulePinsOne() {
        FiscalXmlDocumentItem item = itemWith("P002", "5102", "11111111");
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(item));

        ProductFiscalRule rule = ruleWith("P002", "5102", "22222222");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

        List<ProductFiscalDivergenceDTO> result = service.findDivergences(companyId.toString(), null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).ncmAtual()).isEqualTo("11111111");
        assertThat(result.get(0).ncmCorreto()).isEqualTo("22222222");
    }

    @Test
    void doesNotFlagAnItemThatMatchesTheRule() {
        FiscalXmlDocumentItem item = itemWith("P003", "5102", "12345678");
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(item));

        ProductFiscalRule rule = ruleWith("P003", "5102", "12345678");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

        assertThat(service.findDivergences(companyId.toString(), null, null)).isEmpty();
    }

    @Test
    void skipsItemsWithNoRegisteredRule() {
        FiscalXmlDocumentItem item = itemWith("P004", "5405", "12345678");
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(item));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(ruleWith("OUTRO", "5102", null)));

        assertThat(service.findDivergences(companyId.toString(), null, null)).isEmpty();
    }

    @Test
    void aNoteRectifiedWithTheCurrentRuleNoLongerDiverges() {
        FiscalXmlDocumentItem item = itemWith("P005", "5405", "11111111");
        item.getDocument().setRectifiedFilePath("s3://bucket/fiscal-xml/retificado.xml");
        item.getDocument().setLastRectificationCheckedAt(LocalDateTime.now());
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(item));
        ProductFiscalRule rule = ruleWith("P005", "5102", "22222222");
        ReflectionTestUtils.setField(rule, "lastModifiedDate", LocalDateTime.now().minusDays(1));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

        assertThat(service.findDivergences(companyId.toString(), null, null)).isEmpty();
    }

    @Test
    void theCompanysOwnNotesAreNotCheckedAgainstTheEntryRules() {
        FiscalXmlDocumentItem sale = itemWith("P010", "5102", "11111111");
        sale.getDocument().setOwnIssue(true);
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(sale));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(ruleWith("P010", "1102", null)));

        assertThat(service.findDivergences(companyId.toString(), null, null)).isEmpty();
    }

    @Test
    void theRuleCfopTakesTheFirstDigitOfTheNoteOperation() {
        FiscalXmlDocumentItem interstate = itemWith("P011", "2102", "11111111");
        interstate.getDocument().setDestinoOperacao("2");
        FiscalXmlDocumentItem internalWrong = itemWith("P011", "2102", "11111111");
        internalWrong.getDocument().setDestinoOperacao("1");
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(interstate, internalWrong));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(ruleWith("P011", "1102", null)));

        List<ProductFiscalDivergenceDTO> result = service.findDivergences(companyId.toString(), null, null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().cfopAtual()).isEqualTo("2102");
        assertThat(result.getFirst().cfopCorreto()).isEqualTo("1102");
    }

    @Test
    void aSupplierRuleOnlyAppliesToThatSuppliersNotes() {
        FiscalXmlDocumentItem fromA = itemWith("001", "5102", "11111111");
        fromA.getDocument().setIssuerCnpj("11222333000181");
        FiscalXmlDocumentItem fromB = itemWith("001", "5102", "11111111");
        fromB.getDocument().setIssuerCnpj("99888777000166");
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(fromA, fromB));
        ProductFiscalRule ofA = ruleWith("001", "1102", null);
        ofA.setSupplierDocument("11222333000181");
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(ofA));

        List<ProductFiscalDivergenceDTO> result = service.findDivergences(companyId.toString(), null, null);

        assertThat(result).extracting(ProductFiscalDivergenceDTO::documentId).containsExactly(fromA.getDocument().getId().toString());
    }

    @Test
    void canceledAndDeletedNotesAreNotDivergences() {
        FiscalXmlDocumentItem canceled = itemWith("P007", "5405", "11111111");
        canceled.getDocument().setStatus(br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED);
        FiscalXmlDocumentItem deleted = itemWith("P007", "5405", "11111111");
        deleted.getDocument().setDeleted(true);
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(canceled, deleted));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(ruleWith("P007", "5102", null)));

        assertThat(service.findDivergences(companyId.toString(), null, null)).isEmpty();
    }

    @Test
    void aRuleEditedAfterTheRectificationMakesTheNoteDivergeAgain() {
        FiscalXmlDocumentItem item = itemWith("P006", "5405", "11111111");
        item.getDocument().setRectifiedFilePath("s3://bucket/fiscal-xml/retificado.xml");
        item.getDocument().setLastRectificationCheckedAt(LocalDateTime.now().minusDays(2));
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(item));
        ProductFiscalRule rule = ruleWith("P006", "6102", null);
        ReflectionTestUtils.setField(rule, "lastModifiedDate", LocalDateTime.now().minusDays(1));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId)).thenReturn(List.of(rule));

        assertThat(service.findDivergences(companyId.toString(), null, null)).hasSize(1);
    }

    private FiscalXmlDocumentItem itemWith(String codigo, String cfop, String ncm) {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setId(UUID.randomUUID());
        document.setAccessKey("35240112345678000199550010000000011000000010");
        document.setIssueDate(LocalDateTime.now());

        FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
        item.setDocument(document);
        item.setCodigo(codigo);
        item.setCfop(cfop);
        item.setNcm(ncm);
        item.setDescricao("Produto " + codigo);
        return item;
    }

    private ProductFiscalRule ruleWith(String codigoProduto, String cfopCorreto, String ncmCorreto) {
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setProductCode(codigoProduto);
        rule.setCorrectCfop(cfopCorreto);
        rule.setCorrectNcm(ncmCorreto);
        return rule;
    }
}
