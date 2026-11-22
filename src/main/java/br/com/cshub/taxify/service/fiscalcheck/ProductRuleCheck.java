package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductTaxRule;
import br.com.cshub.taxify.domain.xml.FiscalItemIbsCbs;
import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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

    @Override
    public List<FiscalDivergence> check(FiscalXmlDocumentItem item, FiscalCheckContext context) {
        return context.ruleFor(item.getCodigo())
                .map(rule -> compare(item, rule))
                .orElse(List.of());
    }

    private List<FiscalDivergence> compare(FiscalXmlDocumentItem item, ProductFiscalRule rule) {
        List<FiscalDivergence> divergences = new ArrayList<>();
        text(divergences, CFOP, "CFOP", "CFOP", item.getCfop(), rule.getCfopCorreto(), true);
        text(divergences, NCM, "NCM", "NCM", item.getNcm(), rule.getNcmCorreto(), true);

        ProductTaxRule tax = rule.getTaxRule();
        if (tax == null) {
            return divergences;
        }
        FiscalItemTaxDetail detail = item.getTaxDetail();
        if (detail != null) {
            text(divergences, ORIGEM, "orig", "Origem", detail.origemMercadoria(), tax.origemMercadoria(), false);
            text(divergences, CEST, "CEST", "CEST", detail.cest(), tax.cest(), false);
            if (detail.csosn() != null) {
                text(divergences, CSOSN, "CSOSN", "CSOSN", detail.csosn(), tax.csosn(), false);
            } else {
                text(divergences, CST_ICMS, "CST_ICMS", "CST de ICMS", item.getSituacaoTributariaIcms(), tax.cstIcms(), false);
            }
            rate(divergences, ALIQUOTA_IPI, "pIPI", "Alíquota de IPI", detail.aliquotaIpi(), tax.aliquotaIpi());
            rate(divergences, ALIQUOTA_PIS, "pPIS", "Alíquota de PIS", detail.aliquotaPis(), tax.aliquotaPis());
            rate(divergences, ALIQUOTA_COFINS, "pCOFINS", "Alíquota de COFINS", detail.aliquotaCofins(), tax.aliquotaCofins());
        }
        if (isInternal(item.getDocument())) {
            rate(divergences, ALIQUOTA_ICMS, "pICMS", "Alíquota interna de ICMS", item.getAliquotaIcms(), tax.aliquotaIcms());
        }
        text(divergences, CST_IPI, "CST_IPI", "CST de IPI", item.getSituacaoTributariaIpi(), tax.cstIpi(), false);
        text(divergences, CST_PIS, "CST_PIS", "CST de PIS", item.getSituacaoTributariaPis(), tax.cstPis(), false);
        text(divergences, CST_COFINS, "CST_COFINS", "CST de COFINS", item.getSituacaoTributariaCofins(), tax.cstCofins(), false);
        FiscalItemIbsCbs ibsCbs = item.getIbsCbs();
        if (ibsCbs != null) {
            text(divergences, CST_IBSCBS, "CST_IBSCBS", "CST de IBS/CBS", ibsCbs.cst(), tax.cstIbsCbs(), false);
            text(divergences, CLASS_TRIB, "cClassTrib", "cClassTrib", ibsCbs.classificacaoTributaria(), tax.classificacaoTributaria(), false);
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

    private static boolean isInternal(FiscalXmlDocument document) {
        return document != null && INTERNAL.equals(trim(document.getDestinoOperacao()));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
