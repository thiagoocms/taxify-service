package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscaldocument.ItemIcms;
import br.com.cshub.taxify.domain.fiscaldocument.ItemIcmsSt;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The item's tax values against its own codes and bases - nothing registered needed:
 * <ul>
 *   <li><b>code x value</b> - a code that means "no tax" (ICMS CST 40/41/50/60 or CSOSN
 *       102/103/300/400/500; IPI CST 01-05 and 51-55; PIS/COFINS CST 04-09) with a value above zero;</li>
 *   <li><b>calculation</b> - value = base x rate within one cent: ICMS (CST 00/10/20/70/90 -
 *       the base already carries any reduction), IPI, PIS and COFINS (ad valorem only), and
 *       ICMS-ST for CST 10/70 (ST base x ST rate minus the item's own ICMS; a warning, since
 *       FCP-ST and state specifics can shift it).</li>
 * </ul>
 * A value or base the note doesn't have is not compared.
 */
@Component
public class TaxCalculationCheck implements FiscalCheck {

    public static final String ZERO_TAX = "TAX_CODE_VALUE";
    public static final String CALCULATION = "TAX_CALCULATION";

    private static final Set<String> ICMS_NO_TAX = Set.of("40", "41", "50", "60", "102", "103", "300", "400", "500");
    private static final Set<String> ICMS_CALCULATED = Set.of("00", "10", "20", "70", "90");
    private static final Set<String> ICMS_ST_CALCULATED = Set.of("10", "70");
    private static final Set<String> IPI_NO_TAX = Set.of("01", "02", "03", "04", "05", "51", "52", "53", "54", "55");
    private static final Set<String> PIS_COFINS_NO_TAX = Set.of("04", "05", "06", "07", "08", "09");

    @Override
    public List<FiscalDivergence> check(FiscalDocumentItem item, FiscalCheckContext context) {
        List<FiscalDivergence> divergences = new ArrayList<>();
        ItemIcms icms = item.getIcms();
        String cstIcms = trim(icms.cst());
        boolean withDetail = item.hasTaxDetail();

        noTax(divergences, "vICMS", "ICMS", cstIcms, ICMS_NO_TAX, icms.amount());
        noTax(divergences, "vIPI", "IPI", trim(item.getIpi().cst()), IPI_NO_TAX, item.getIpi().amount());
        noTax(divergences, "vPIS", "PIS", trim(item.getPis().cst()), PIS_COFINS_NO_TAX, item.getPis().amount());
        noTax(divergences, "vCOFINS", "COFINS", trim(item.getCofins().cst()), PIS_COFINS_NO_TAX, item.getCofins().amount());

        boolean regimeNormalCst = !withDetail || icms.csosn() == null;
        if (regimeNormalCst && cstIcms != null && ICMS_CALCULATED.contains(cstIcms)) {
            calculated(divergences, "vICMS", "ICMS", icms.base(), icms.rate(), icms.amount(), DivergenceSeverity.ERROR);
        }
        if (withDetail) {
            calculated(divergences, "vIPI", "IPI", item.getIpi().base(), item.getIpi().rate(), item.getIpi().amount(),
                    DivergenceSeverity.ERROR);
            calculated(divergences, "vPIS", "PIS", item.getPis().base(), item.getPis().rate(), item.getPis().amount(),
                    DivergenceSeverity.ERROR);
            calculated(divergences, "vCOFINS", "COFINS", item.getCofins().base(), item.getCofins().rate(),
                    item.getCofins().amount(), DivergenceSeverity.ERROR);
            if (regimeNormalCst && cstIcms != null && ICMS_ST_CALCULATED.contains(cstIcms)) {
                icmsSt(divergences, item.getIcmsSt(), icms.amount());
            }
        }
        return divergences;
    }

    private static void noTax(List<FiscalDivergence> divergences, String field, String label, String code,
                              Set<String> noTaxCodes, BigDecimal value) {
        if (code != null && noTaxCodes.contains(code) && value != null && value.signum() > 0) {
            divergences.add(new FiscalDivergence(ZERO_TAX, field, Money.show(value), "0", DivergenceSeverity.ERROR,
                    "%s de %s destacado com código %s, que não tem imposto".formatted(label, Money.show(value), code)));
        }
    }

    private static void calculated(List<FiscalDivergence> divergences, String field, String label, BigDecimal base,
                                   BigDecimal rate, BigDecimal value, DivergenceSeverity severity) {
        if (base == null || rate == null || value == null) {
            return;
        }
        BigDecimal expected = Money.tax(base, rate, null);
        if (Money.differs(value, expected)) {
            divergences.add(new FiscalDivergence(CALCULATION, field, Money.show(value), Money.show(expected), severity,
                    "Valor de %s %s diferente de base x alíquota (%s x %s%% = %s)"
                            .formatted(label, Money.show(value), Money.show(base), Money.show(rate), Money.show(expected))));
        }
    }

    private static void icmsSt(List<FiscalDivergence> divergences, ItemIcmsSt st, BigDecimal ownIcms) {
        if (st.base() == null || st.rate() == null || st.amount() == null) {
            return;
        }
        BigDecimal expected = Money.tax(st.base(), st.rate(), null).subtract(Money.orZero(ownIcms));
        if (Money.differs(st.amount(), expected)) {
            divergences.add(new FiscalDivergence(CALCULATION, "vICMSST", Money.show(st.amount()), Money.show(expected),
                    DivergenceSeverity.WARNING,
                    "ICMS-ST %s diferente de base ST x alíquota ST - ICMS próprio (%s)"
                            .formatted(Money.show(st.amount()), Money.show(expected))));
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
