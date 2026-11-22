package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FiscalCheckEngineTest {

    private final ProductFiscalRuleRepository ruleRepository = mock(ProductFiscalRuleRepository.class);
    private final UUID companyId = UUID.randomUUID();

    @Test
    void loadsTheRulesOnceAndRunsEveryCheck() {
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(companyId))
                .thenReturn(List.of(rule("P1", "5102", "12345678")));
        FiscalCheck alwaysWarns = (item, context) -> List.of(
                new FiscalDivergence("TEST", "X", null, null, DivergenceSeverity.WARNING, "test"));
        FiscalCheckEngine engine = new FiscalCheckEngine(List.of(new ProductRuleCheck(), alwaysWarns), List.of(), ruleRepository,
                mock(br.com.cshub.taxify.repository.IbsCbsRateRepository.class));

        FiscalCheckContext context = engine.contextFor(companyId);
        List<FiscalDivergence> first = engine.check(item("P1", "5405", "87654321"), context);
        List<FiscalDivergence> second = engine.check(item("P1", "5102", "12345678"), context);

        assertThat(first).extracting(FiscalDivergence::code)
                .containsExactly(ProductRuleCheck.CFOP, ProductRuleCheck.NCM, "TEST");
        assertThat(first.getFirst().found()).isEqualTo("5405");
        assertThat(first.getFirst().expected()).isEqualTo("5102");
        assertThat(first.getFirst().severity()).isEqualTo(DivergenceSeverity.ERROR);
        assertThat(second).extracting(FiscalDivergence::code).containsExactly("TEST");
        verify(ruleRepository, times(1)).findByCompany_IdAndDeletedIsFalse(companyId);
    }

    @Test
    void ruleCheckIgnoresNcmWhenTheRuleDoesNotPinOneAndItemsWithoutRule() {
        FiscalCheckContext context = new FiscalCheckContext(companyId, java.util.Map.of("P1", rule("P1", "5102", null)));
        ProductRuleCheck check = new ProductRuleCheck();

        assertThat(check.check(item("P1", " 5102 ", "99999999"), context)).isEmpty();
        assertThat(check.check(item("SEM_REGRA", "5405", "1"), context)).isEmpty();
        assertThat(check.check(item(null, "5405", "1"), context)).isEmpty();
    }

    private static FiscalXmlDocumentItem item(String codigo, String cfop, String ncm) {
        FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
        item.setCodigo(codigo);
        item.setCfop(cfop);
        item.setNcm(ncm);
        return item;
    }

    private static ProductFiscalRule rule(String codigo, String cfop, String ncm) {
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setCodigoProduto(codigo);
        rule.setCfopCorreto(cfop);
        rule.setNcmCorreto(ncm);
        return rule;
    }
}
