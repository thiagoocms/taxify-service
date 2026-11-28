package br.com.cshub.taxify.service;

import br.com.cshub.taxify.service.fiscalcheck.ProductRuleCheck;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductTaxRule;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIbsCbs;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;

import java.util.Objects;

/**
 * Tells, from the item's stored columns alone, whether {@link FiscalXmlRectificationService}
 * could change anything in it - so a note whose items already match their rules is settled
 * without reading its XML from storage. Errs on the side of "maybe": a field differing from
 * the rule is enough, even when the rectifier would then leave it alone (a CST that needs
 * another XML group); a field the note doesn't have (null column) is never rewritten there,
 * so it doesn't count here either. Only valid for columns written by the current parser.
 */
final class RectificationPrecheck {

    private RectificationPrecheck() {
    }

    static boolean mayChange(FiscalDocumentItem item, ProductFiscalRule rule) {
        if (differs(item.cfop(), ProductRuleCheck.expectedCfop(item, rule))) {
            return true;
        }
        if (rule.getCorrectNcm() != null && differs(item.getProduct().ncm(), rule.getCorrectNcm())) {
            return true;
        }
        ProductTaxRule tax = rule.getTaxRule();
        if (tax == null) {
            return false;
        }
        boolean withDetail = item.hasTaxDetail();
        String csosn = withDetail ? item.getIcms().csosn() : null;
        ItemIbsCbs ibsCbs = item.getIbsCbs();
        return pinnedAndDiffers(withDetail ? item.getProduct().cest() : null, tax.cest())
                || pinnedAndDiffers(withDetail ? item.getIcms().origin() : null, tax.goodsOrigin())
                // The ICMS group holds either a CSOSN (Simples) or a CST - the column has whichever it is.
                || (csosn != null
                        ? pinnedAndDiffers(csosn, tax.csosn())
                        : pinnedAndDiffers(item.getIcms().cst(), tax.cstIcms()))
                || pinnedAndDiffers(item.getIpi().cst(), tax.cstIpi())
                || pinnedAndDiffers(item.getPis().cst(), tax.cstPis())
                || pinnedAndDiffers(item.getCofins().cst(), tax.cstCofins())
                || pinnedAndDiffers(ibsCbs == null ? null : ibsCbs.classCode(), tax.taxClassification());
    }

    /** CFOP/NCM: rewritten whenever the tag exists and differs. */
    private static boolean differs(String current, String expected) {
        return current != null && !Objects.equals(trim(current), trim(expected));
    }

    /** Tax codes: only when the rule pins one and the note has the field. */
    private static boolean pinnedAndDiffers(String current, String expected) {
        return expected != null && current != null && !Objects.equals(trim(current), trim(expected));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
