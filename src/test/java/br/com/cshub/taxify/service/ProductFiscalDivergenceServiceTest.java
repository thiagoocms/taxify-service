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
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        service = new ProductFiscalDivergenceService(itemRepository, ruleRepository, companyValidation, messageService);
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
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P001"))
                .thenReturn(java.util.Optional.of(rule));

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
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P002"))
                .thenReturn(java.util.Optional.of(rule));

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
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P003"))
                .thenReturn(java.util.Optional.of(rule));

        assertThat(service.findDivergences(companyId.toString(), null, null)).isEmpty();
    }

    @Test
    void skipsItemsWithNoRegisteredRule() {
        FiscalXmlDocumentItem item = itemWith("P004", "5405", "12345678");
        when(itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(eq(companyId), any(), any()))
                .thenReturn(List.of(item));
        when(ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(companyId, "P004"))
                .thenReturn(java.util.Optional.empty());

        assertThat(service.findDivergences(companyId.toString(), null, null)).isEmpty();
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
        rule.setCodigoProduto(codigoProduto);
        rule.setCfopCorreto(cfopCorreto);
        rule.setNcmCorreto(ncmCorreto);
        return rule;
    }
}
