package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductRules;

/**
 * FD-RN-57: the CFOP an item has in the rectified XML - the same rule the notes screen already used to show the
 * rectified CFOP ({@code FiscalXmlQueryService}): the item's {@code ProductFiscalRule} (the supplier's rule, else the
 * code's general rule), when the note's rectification was checked after the rule last changed
 * ({@link ProductRuleCheck#rectifiedUnder}), gives its CFOP with the first digit following the note's destination
 * ({@link OperationCfops#forOperation}); an item without CFOP has none in the rectified XML either. Otherwise the item
 * keeps its own CFOP (null here). Pure; the SQL of V87 ({@code CopyMappings.rectifiedEffectiveCfop}) applies the same
 * rule to the copied notes and the FD-RN-55 test compares both.
 */
public final class EffectiveCfops {

    private EffectiveCfops() {
    }

    /** The rectified CFOP of {@code item} under {@code rules}; null = the item keeps its own. */
    public static String rectified(ProductRules rules, FiscalDocumentItem item) {
        GoodsInvoice invoice = item.getDocument();
        if (invoice == null || item.getProduct().cfop() == null) {
            return null;
        }
        ProductFiscalRule rule = rules.forItem(item).orElse(null);
        if (rule == null || rule.getCorrectCfop() == null || !ProductRuleCheck.rectifiedUnder(invoice, rule)) {
            return null;
        }
        return OperationCfops.forOperation(rule.getCorrectCfop(), invoice.getHeader().destinationType()).strip();
    }
}
