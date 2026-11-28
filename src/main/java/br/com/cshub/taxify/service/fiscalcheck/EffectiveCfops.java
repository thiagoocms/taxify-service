package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscalrule.ProductRules;

/**
 * FD-RN-57: the CFOP an item has in the rectified XML - the one {@code FiscalXmlRectificationService} writes: the CFOP
 * of the item's {@code ProductFiscalRule} (the supplier's rule, else the code's general rule) with the first digit
 * following the note's destination ({@link OperationCfops#forOperation}). No rule = the rectified XML keeps the item's
 * CFOP (null here). Pure; the SQL of V87 ({@code CopyMappings.RECTIFIED_EFFECTIVE_CFOP}) applies the same rule to the
 * copied notes and the FD-RN-55 test compares both.
 */
public final class EffectiveCfops {

    private EffectiveCfops() {
    }

    /** The rectified CFOP of {@code item} under {@code rules}; null = no rule for it (the item keeps its own). */
    public static String rectified(ProductRules rules, FiscalDocumentItem item) {
        GoodsInvoice invoice = item.getDocument();
        String destination = invoice == null ? null : invoice.getHeader().destinationType();
        return rules.forItem(item).map(rule -> OperationCfops.forOperation(rule.getCorrectCfop(), destination)).orElse(null);
    }
}
