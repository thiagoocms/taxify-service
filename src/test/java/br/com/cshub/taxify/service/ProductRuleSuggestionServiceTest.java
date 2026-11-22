package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductRuleSuggestionServiceTest {

    private final FiscalXmlDocumentItemRepository itemRepository = mock(FiscalXmlDocumentItemRepository.class);
    private final ProductFiscalRuleRepository ruleRepository = mock(ProductFiscalRuleRepository.class);
    private final CompanyValidation companyValidation = mock(CompanyValidation.class);
    private final ProductRuleSuggestionService service = new ProductRuleSuggestionService(itemRepository, ruleRepository,
            companyValidation, mock(MessageService.class));
    private final UUID companyId = UUID.randomUUID();

    @Test
    void suggestsTheMostFrequentValueOfEachFieldWithItsShare() {
        when(companyValidation.checkExist(companyId)).thenReturn(new Company(companyId));
        when(itemRepository.findClassificationsByProduct(companyId, FiscalDirection.OUTBOUND)).thenReturn(List.of(
                row("P1", "Refrigerante", "22021000", "5405", "0", "60", null, null, "0300700", "04", 30),
                row("P1", "Refrigerante 2L", "22021000", "5102", "0", "00", null, new BigDecimal("18.0000"), "0300700", "04", 10),
                row("P2", "Agua", "22011000", "5102", "0", "00", null, new BigDecimal("18.0000"), null, "01", 5),
                row("P3", "Tem regra", "1", "5102", "0", "00", null, null, null, "01", 50)));
        when(ruleRepository.findCodigosWithRule(eq(companyId), any())).thenReturn(List.of("P3"));

        List<ProductRuleSuggestionDTO> result = service.suggest(companyId.toString(), FiscalDirection.OUTBOUND, true);

        assertThat(result).extracting(ProductRuleSuggestionDTO::codigoProduto).containsExactly("P1", "P2");
        ProductRuleSuggestionDTO p1 = result.getFirst();
        assertThat(p1.descricaoProduto()).isEqualTo("Refrigerante");
        assertThat(p1.ocorrencias()).isEqualTo(40);
        assertThat(p1.campos().get("cfopCorreto").valor()).isEqualTo("5405");
        assertThat(p1.campos().get("cfopCorreto").percentual()).isEqualByComparingTo("75.0");
        assertThat(p1.campos().get("cstIcms").valor()).isEqualTo("60");
        // the internal rate only shows on 10 of the items - 100% of those that have it
        assertThat(p1.campos().get("aliquotaIcms").valor()).isEqualTo("18");
        assertThat(p1.campos().get("aliquotaIcms").percentual()).isEqualByComparingTo("100.0");
        assertThat(p1.campos().get("cstPis").valor()).isEqualTo("04");
        assertThat(p1.campos()).doesNotContainKey("csosn");

        List<ProductRuleSuggestionDTO> all = service.suggest(companyId.toString(), FiscalDirection.OUTBOUND, false);
        assertThat(all).extracting(ProductRuleSuggestionDTO::codigoProduto, ProductRuleSuggestionDTO::possuiRegra)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("P3", true),
                        org.assertj.core.groups.Tuple.tuple("P1", false),
                        org.assertj.core.groups.Tuple.tuple("P2", false));
    }

    /** codigo, descricao, ncm, cfop, origem, cst, csosn, pICMS interno, cest, cst PIS + the rest null, count. */
    private static Object[] row(String codigo, String descricao, String ncm, String cfop, String origem, String cst,
                                String csosn, BigDecimal aliquotaIcms, String cest, String cstPis, long count) {
        return new Object[]{codigo, descricao, ncm, cfop, origem, cst, csosn, aliquotaIcms, cest,
                null, null, cstPis, null, null, null, null, null, count};
    }
}
