package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsTotals;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The note's ICMSTot against the sum of its items: ICMS base and value, ICMS-ST, IPI, PIS and
 * COFINS, within one cent. A total the note doesn't have is not compared; ICMS-ST only when
 * every item has its tax detail (otherwise the sum would be incomplete).
 */
@Component
public class IcmsTotalsCheck implements FiscalDocumentCheck {

    public static final String CODE = "ICMS_TOTALS";

    @Override
    public List<FiscalDivergence> check(GoodsInvoice document, List<FiscalDocumentItem> items, FiscalCheckContext context) {
        if (items.isEmpty()) {
            return List.of();
        }
        GoodsTotals totals = document.getGoodsTotals();
        List<FiscalDivergence> divergences = new ArrayList<>();
        compare(divergences, "vBC", "Base de ICMS", totals.icmsBase(), items, item -> item.getIcms().base());
        compare(divergences, "vICMS", "ICMS", totals.icmsAmount(), items, item -> item.getIcms().amount());
        compare(divergences, "vIPI", "IPI", totals.ipiAmount(), items, item -> item.getIpi().amount());
        compare(divergences, "vPIS", "PIS", totals.pisAmount(), items, item -> item.getPis().amount());
        compare(divergences, "vCOFINS", "COFINS", totals.cofinsAmount(), items, item -> item.getCofins().amount());
        if (items.stream().allMatch(FiscalDocumentItem::hasTaxDetail)) {
            compare(divergences, "vST", "ICMS-ST", totals.icmsStAmount(), items, item -> item.getIcmsSt().amount());
        }
        return divergences;
    }

    private static void compare(List<FiscalDivergence> divergences, String field, String label, BigDecimal total,
                                List<FiscalDocumentItem> items, Function<FiscalDocumentItem, BigDecimal> value) {
        if (total == null) {
            return;
        }
        BigDecimal sum = items.stream().map(value).map(Money::orZero).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Money.differs(total, sum)) {
            divergences.add(new FiscalDivergence(CODE, field, Money.show(total), Money.show(sum), DivergenceSeverity.ERROR,
                    "%s no total da nota (%s) diferente da soma dos itens (%s)".formatted(label, Money.show(total), Money.show(sum))));
        }
    }
}
