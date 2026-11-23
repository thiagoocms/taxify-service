package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ICMS substituição tributária (ST) on the item:
 * <ul>
 *   <li><b>ST CFOP x CST</b> - a sale with an ST CFOP (5401/5402/5403/5405, 6401/6402/6403/6404) must
 *       carry an ST code: CST 10, 30, 60, 70 or 90, or CSOSN 201, 202, 203, 500 or 900;</li>
 *   <li><b>CEST</b> - an item with an ST code (CST 10/30/60/70, CSOSN 201/202/203/500) must inform the
 *       CEST (Convênio ICMS 142/2018).</li>
 * </ul>
 * Items without the tax detail (imported before it existed) are skipped. Warnings: the state may
 * have specifics the note alone can't show.
 */
@Component
public class SubstitutionCheck implements FiscalCheck {

    public static final String CODE_CFOP = "ST_CFOP_CST";
    public static final String CODE_CEST = "ST_CEST_MISSING";

    static final Set<String> ST_CFOPS = Set.of("5401", "5402", "5403", "5405", "6401", "6402", "6403", "6404");
    private static final Set<String> ST_CSTS = Set.of("10", "30", "60", "70", "90");
    private static final Set<String> ST_CSOSNS = Set.of("201", "202", "203", "500", "900");
    /** CST/CSOSN that mean the item is under ST (90 / 900 "others" don't say it - no CEST demand). */
    private static final Set<String> CEST_CSTS = Set.of("10", "30", "60", "70");
    private static final Set<String> CEST_CSOSNS = Set.of("201", "202", "203", "500");

    @Override
    public List<FiscalDivergence> check(FiscalXmlDocumentItem item, FiscalCheckContext context) {
        if (item.getTaxDetail() == null || item.getSituacaoTributariaIcms() == null) {
            return List.of();
        }
        String csosn = digits(item.getTaxDetail().csosn());
        String cst = csosn == null ? lastTwo(item.getSituacaoTributariaIcms()) : null;
        String code = csosn != null ? csosn : cst;
        String cfop = item.getCfop() == null ? "" : item.getCfop().strip();
        List<FiscalDivergence> divergences = new ArrayList<>();

        boolean stCode = csosn != null ? ST_CSOSNS.contains(csosn) : ST_CSTS.contains(cst);
        if (ST_CFOPS.contains(cfop) && !stCode) {
            divergences.add(new FiscalDivergence(CODE_CFOP, csosn != null ? "CSOSN" : "CST_ICMS", code,
                    csosn != null ? "201/202/203/500/900" : "10/30/60/70/90", DivergenceSeverity.WARNING,
                    "CFOP %s é de operação com substituição tributária, mas o item informa %s %s".formatted(
                            cfop, csosn != null ? "CSOSN" : "CST", code)));
        }
        boolean needsCest = csosn != null ? CEST_CSOSNS.contains(csosn) : CEST_CSTS.contains(cst);
        String cest = item.getTaxDetail().cest();
        if (needsCest && (cest == null || cest.isBlank())) {
            divergences.add(new FiscalDivergence(CODE_CEST, "CEST", null, "CEST do item", DivergenceSeverity.WARNING,
                    "Item com ICMS-ST (%s %s) sem CEST - obrigatório nas mercadorias sujeitas a ST (Conv. ICMS 142/2018)"
                            .formatted(csosn != null ? "CSOSN" : "CST", code)));
        }
        return divergences;
    }

    private static String digits(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    /** CST may come with the origin in front ("060") - the last two digits are the CST. */
    private static String lastTwo(String value) {
        String digits = digits(value);
        return digits == null ? null : digits.length() > 2 ? digits.substring(digits.length() - 2) : digits;
    }
}
