package br.com.cshub.taxify.service;

import br.com.cshub.taxify.service.fiscalcheck.ProductRuleCheck;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductTaxRule;
import br.com.cshub.taxify.domain.xml.FiscalItemIbsCbs;
import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;

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

    static boolean mayChange(FiscalXmlDocumentItem item, ProductFiscalRule rule) {
        if (differs(item.getCfop(), ProductRuleCheck.expectedCfop(item, rule))) {
            return true;
        }
        if (rule.getNcmCorreto() != null && differs(item.getNcm(), rule.getNcmCorreto())) {
            return true;
        }
        ProductTaxRule tax = rule.getTaxRule();
        if (tax == null) {
            return false;
        }
        FiscalItemTaxDetail detail = item.getTaxDetail();
        String csosn = detail == null ? null : detail.csosn();
        FiscalItemIbsCbs ibsCbs = item.getIbsCbs();
        return pinnedAndDiffers(detail == null ? null : detail.cest(), tax.cest())
                || pinnedAndDiffers(detail == null ? null : detail.origemMercadoria(), tax.origemMercadoria())
                // The ICMS group holds either a CSOSN (Simples) or a CST - the column has whichever it is.
                || (csosn != null
                        ? pinnedAndDiffers(csosn, tax.csosn())
                        : pinnedAndDiffers(item.getSituacaoTributariaIcms(), tax.cstIcms()))
                || pinnedAndDiffers(item.getSituacaoTributariaIpi(), tax.cstIpi())
                || pinnedAndDiffers(item.getSituacaoTributariaPis(), tax.cstPis())
                || pinnedAndDiffers(item.getSituacaoTributariaCofins(), tax.cstCofins())
                || pinnedAndDiffers(ibsCbs == null ? null : ibsCbs.classificacaoTributaria(), tax.classificacaoTributaria());
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
