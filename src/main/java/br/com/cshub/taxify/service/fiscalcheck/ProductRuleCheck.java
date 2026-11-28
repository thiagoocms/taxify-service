package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductTaxRule;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIbsCbs;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIcms;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The item against the {@link ProductFiscalRule} registered for its product. No rule for the
 * product → nothing to compare. CFOP always; every other field only when the rule sets it:
 * NCM, and the {@link ProductTaxRule} fields - origin, CST <em>or</em> CSOSN (whichever the
 * note used), internal ICMS rate (internal operations only), CEST, CST/rate of IPI, PIS and
 * COFINS, CST and cClassTrib of IBS/CBS. A field the note doesn't have (item imported before
 * the tax detail existed, no IPI group...) is not compared.
 */
@Component
public class ProductRuleCheck implements FiscalCheck {

    public static final String CFOP = "RULE_CFOP";
    public static final String NCM = "RULE_NCM";
    public static final String ORIGEM = "RULE_ORIGEM";
    public static final String CST_ICMS = "RULE_CST_ICMS";
    public static final String CSOSN = "RULE_CSOSN";
    public static final String ALIQUOTA_ICMS = "RULE_ALIQUOTA_ICMS";
    public static final String CEST = "RULE_CEST";
    public static final String CST_IPI = "RULE_CST_IPI";
    public static final String ALIQUOTA_IPI = "RULE_ALIQUOTA_IPI";
    public static final String CST_PIS = "RULE_CST_PIS";
    public static final String ALIQUOTA_PIS = "RULE_ALIQUOTA_PIS";
    public static final String CST_COFINS = "RULE_CST_COFINS";
    public static final String ALIQUOTA_COFINS = "RULE_ALIQUOTA_COFINS";
    public static final String CST_IBSCBS = "RULE_CST_IBSCBS";
    public static final String CLASS_TRIB = "RULE_CLASS_TRIB";

    /** ide/idDest of an internal operation. */
    private static final String INTERNAL = "1";

    /**
     * Only notes the company received: the rules describe how a purchase is booked (entry CFOP,
     * the supplier's product code) and only those notes are rectified - the company's own notes
     * would all "diverge" from an entry CFOP.
     */
    @Override
    public List<FiscalDivergence> check(FiscalDocumentItem item, FiscalCheckContext context) {
        if (item.getDocument() != null && item.getDocument().isOwnIssue()) {
            return List.of();
        }
        return context.ruleFor(item)
                .map(rule -> compare(item, rule))
                .orElse(List.of());
    }

    /** The rule's CFOP with the first digit of this note's operation (1102 → 2102 interstate). */
    public static String expectedCfop(FiscalDocumentItem item, ProductFiscalRule rule) {
        return OperationCfops.forOperation(rule.getCorrectCfop(),
                item.getDocument() == null ? null : item.getDocument().getHeader().destinationType());
    }

    private List<FiscalDivergence> compare(FiscalDocumentItem item, ProductFiscalRule rule) {
        List<FiscalDivergence> divergences = new ArrayList<>();
        if (!rectifiedUnder(item.getDocument(), rule)) {
            text(divergences, CFOP, "CFOP", "CFOP", item.cfop(), expectedCfop(item, rule), true);
            text(divergences, NCM, "NCM", "NCM", item.getProduct().ncm(), rule.getCorrectNcm(), true);
        }

        ProductTaxRule tax = rule.getTaxRule();
        if (tax == null) {
            return divergences;
        }
        ItemIcms icms = item.getIcms();
        if (item.hasTaxDetail()) {
            text(divergences, ORIGEM, "orig", "Origem", icms.origin(), tax.goodsOrigin(), false);
            text(divergences, CEST, "CEST", "CEST", item.getProduct().cest(), tax.cest(), false);
            if (icms.csosn() != null) {
                text(divergences, CSOSN, "CSOSN", "CSOSN", icms.csosn(), tax.csosn(), false);
            } else {
                text(divergences, CST_ICMS, "CST_ICMS", "CST de ICMS", icms.cst(), tax.cstIcms(), false);
            }
            rate(divergences, ALIQUOTA_IPI, "pIPI", "Alíquota de IPI", item.getIpi().rate(), tax.ipiRate());
            rate(divergences, ALIQUOTA_PIS, "pPIS", "Alíquota de PIS", item.getPis().rate(), tax.pisRate());
            rate(divergences, ALIQUOTA_COFINS, "pCOFINS", "Alíquota de COFINS", item.getCofins().rate(), tax.cofinsRate());
        }
        if (isInternal(item.getDocument())) {
            rate(divergences, ALIQUOTA_ICMS, "pICMS", "Alíquota interna de ICMS", icms.rate(), tax.icmsRate());
        }
        text(divergences, CST_IPI, "CST_IPI", "CST de IPI", item.getIpi().cst(), tax.cstIpi(), false);
        text(divergences, CST_PIS, "CST_PIS", "CST de PIS", item.getPis().cst(), tax.cstPis(), false);
        text(divergences, CST_COFINS, "CST_COFINS", "CST de COFINS", item.getCofins().cst(), tax.cstCofins(), false);
        ItemIbsCbs ibsCbs = item.getIbsCbs();
        if (ibsCbs != null) {
            text(divergences, CST_IBSCBS, "CST_IBSCBS", "CST de IBS/CBS", ibsCbs.cst(), tax.cstIbsCbs(), false);
            text(divergences, CLASS_TRIB, "cClassTrib", "cClassTrib", ibsCbs.classCode(), tax.taxClassification(), false);
        }
        return divergences;
    }

    /**
     * Compares when the rule sets the field. {@code alsoWhenMissing}: an empty value in the
     * note also diverges (CFOP, as before); otherwise a field the note doesn't have is skipped.
     */
    private static void text(List<FiscalDivergence> divergences, String code, String field, String label,
                             String found, String expected, boolean alsoWhenMissing) {
        if (expected == null || (found == null && !alsoWhenMissing)) {
            return;
        }
        if (!Objects.equals(trim(found), trim(expected))) {
            divergences.add(new FiscalDivergence(code, field, found, expected, DivergenceSeverity.ERROR,
                    "%s %s diferente do cadastrado para o produto (%s)".formatted(label, found, expected)));
        }
    }

    private static void rate(List<FiscalDivergence> divergences, String code, String field, String label,
                             BigDecimal found, BigDecimal expected) {
        if (expected != null && found != null && found.compareTo(expected) != 0) {
            divergences.add(new FiscalDivergence(code, field, Money.show(found), Money.show(expected), DivergenceSeverity.ERROR,
                    "%s %s%% diferente da cadastrada para o produto (%s%%)".formatted(label, Money.show(found), Money.show(expected))));
        }
    }

    /**
     * The note already has a rectified version built with this rule as it stands now: the
     * rectification always rewrites CFOP and NCM to the rule's values, so those no longer diverge.
     * The item columns keep what was issued, hence the check against the rectification itself.
     * A rule edited after the last rectification run makes the rectified copy stale - it diverges again.
     * Tax codes are only swapped within the same XML group, so those are still compared.
     */
    public static boolean rectifiedUnder(GoodsInvoice document, ProductFiscalRule rule) {
        if (document == null || document.getRectifiedFilePath() == null || document.getLastRectificationCheckedAt() == null) {
            return false;
        }
        LocalDateTime ruleChangedAt = rule.getLastModifiedDate() != null ? rule.getLastModifiedDate() : rule.getCreatedDate();
        return ruleChangedAt == null || !ruleChangedAt.isAfter(document.getLastRectificationCheckedAt());
    }

    private static boolean isInternal(GoodsInvoice document) {
        return document != null && INTERNAL.equals(trim(document.getHeader().destinationType()));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
