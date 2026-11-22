package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
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
    public List<FiscalDivergence> check(FiscalXmlDocument document, List<FiscalXmlDocumentItem> items, FiscalCheckContext context) {
        if (items.isEmpty() || !"NFE".equals(document.getDocumentType())) {
            return List.of();
        }
        List<FiscalDivergence> divergences = new ArrayList<>();
        compare(divergences, "vBC", "Base de ICMS", document.getBaseCalculoIcms(), items, FiscalXmlDocumentItem::getBaseCalculoIcms);
        compare(divergences, "vICMS", "ICMS", document.getValorIcms(), items, FiscalXmlDocumentItem::getValorIcms);
        compare(divergences, "vIPI", "IPI", document.getValorIpi(), items, FiscalXmlDocumentItem::getValorIpi);
        compare(divergences, "vPIS", "PIS", document.getValorPis(), items, FiscalXmlDocumentItem::getValorPis);
        compare(divergences, "vCOFINS", "COFINS", document.getValorCofins(), items, FiscalXmlDocumentItem::getValorCofins);
        if (items.stream().map(FiscalXmlDocumentItem::getTaxDetail).allMatch(Objects::nonNull)) {
            compare(divergences, "vST", "ICMS-ST", document.getValorIcmsSt(), items,
                    item -> {
                        FiscalItemTaxDetail detail = item.getTaxDetail();
                        return detail.valorIcmsSt();
                    });
        }
        return divergences;
    }

    private static void compare(List<FiscalDivergence> divergences, String field, String label, BigDecimal total,
                                List<FiscalXmlDocumentItem> items, Function<FiscalXmlDocumentItem, BigDecimal> value) {
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
