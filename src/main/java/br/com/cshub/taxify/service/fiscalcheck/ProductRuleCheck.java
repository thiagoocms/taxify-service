package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The item against the {@link ProductFiscalRule} registered for its product: CFOP always,
 * NCM only when the rule pins one. No rule for the product → nothing to compare.
 */
@Component
public class ProductRuleCheck implements FiscalCheck {

    public static final String CFOP = "RULE_CFOP";
    public static final String NCM = "RULE_NCM";

    @Override
    public List<FiscalDivergence> check(FiscalXmlDocumentItem item, FiscalCheckContext context) {
        return context.ruleFor(item.getCodigo())
                .map(rule -> compare(item, rule))
                .orElse(List.of());
    }

    private List<FiscalDivergence> compare(FiscalXmlDocumentItem item, ProductFiscalRule rule) {
        List<FiscalDivergence> divergences = new ArrayList<>();
        if (!equalsTrimmed(item.getCfop(), rule.getCfopCorreto())) {
            divergences.add(new FiscalDivergence(CFOP, "CFOP", item.getCfop(), rule.getCfopCorreto(), DivergenceSeverity.ERROR,
                    "CFOP %s diferente do cadastrado para o produto (%s)".formatted(item.getCfop(), rule.getCfopCorreto())));
        }
        if (rule.getNcmCorreto() != null && !equalsTrimmed(item.getNcm(), rule.getNcmCorreto())) {
            divergences.add(new FiscalDivergence(NCM, "NCM", item.getNcm(), rule.getNcmCorreto(), DivergenceSeverity.ERROR,
                    "NCM %s diferente do cadastrado para o produto (%s)".formatted(item.getNcm(), rule.getNcmCorreto())));
        }
        return divergences;
    }

    private static boolean equalsTrimmed(String a, String b) {
        return Objects.equals(a == null ? null : a.trim(), b == null ? null : b.trim());
    }
}
