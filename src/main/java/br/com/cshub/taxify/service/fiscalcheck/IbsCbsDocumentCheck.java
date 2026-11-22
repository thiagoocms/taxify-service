package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.xml.FiscalIbsCbsTotals;
import br.com.cshub.taxify.domain.xml.FiscalItemIbsCbs;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * IBS/CBS over the whole note:
 * <ul>
 *   <li><b>group missing</b> - NF-e/NFC-e issued from 2026 by a regime normal issuer (CRT 3)
 *       with no IBSCBS group in any item. A warning: in 2026 the SEFAZ rejection is not
 *       enforced everywhere yet, and Simples Nacional issuers (CRT 1, 2, 4) are exempt;</li>
 *   <li><b>totals</b> - IBSCBSTot (base, vIBS, vCBS) equal to the sum of the items.</li>
 * </ul>
 */
@Component
public class IbsCbsDocumentCheck implements FiscalDocumentCheck {

    public static final String MISSING = "IBSCBS_MISSING";
    public static final String TOTALS = "IBSCBS_TOTALS";

    static final int FIRST_YEAR = 2026;
    private static final String REGIME_NORMAL = "3";
    private static final Set<String> NFE_MODELS = Set.of("55", "65");

    @Override
    public List<FiscalDivergence> check(FiscalXmlDocument document, List<FiscalXmlDocumentItem> items, FiscalCheckContext context) {
        List<FiscalItemIbsCbs> groups = items.stream().map(FiscalXmlDocumentItem::getIbsCbs).filter(Objects::nonNull).toList();
        List<FiscalDivergence> divergences = new ArrayList<>();
        if (groups.isEmpty()) {
            if (!items.isEmpty() && expectsIbsCbs(document)) {
                divergences.add(new FiscalDivergence(MISSING, "IBSCBS", null, null, DivergenceSeverity.WARNING,
                        "Nota de emitente do regime normal emitida a partir de 2026 sem o grupo IBS/CBS"));
            }
            return divergences;
        }
        FiscalIbsCbsTotals totals = document.getIbsCbsTotals();
        if (totals != null) {
            compareTotal("vBCIBSCBS", "Base de IBS/CBS", totals.baseCalculo(), groups, FiscalItemIbsCbs::baseCalculo, divergences);
            compareTotal("vIBS", "IBS", totals.valorIbs(), groups, IbsCbsDocumentCheck::ibsOf, divergences);
            compareTotal("vCBS", "CBS", totals.valorCbs(), groups, FiscalItemIbsCbs::valorCbs, divergences);
        }
        return divergences;
    }

    private static boolean expectsIbsCbs(FiscalXmlDocument document) {
        boolean nfe = document.getModelo() != null ? NFE_MODELS.contains(document.getModelo().trim())
                : "NFE".equals(document.getDocumentType());
        String crt = document.getEmitenteRegimeTributario() == null ? null : document.getEmitenteRegimeTributario().trim();
        return nfe && REGIME_NORMAL.equals(crt)
                && document.getIssueDate() != null && document.getIssueDate().getYear() >= FIRST_YEAR;
    }

    private static BigDecimal ibsOf(FiscalItemIbsCbs group) {
        if (group.valorIbs() != null) {
            return group.valorIbs();
        }
        return group.valorIbsUf() == null && group.valorIbsMun() == null ? null
                : Money.orZero(group.valorIbsUf()).add(Money.orZero(group.valorIbsMun()));
    }

    private void compareTotal(String field, String label, BigDecimal total, List<FiscalItemIbsCbs> groups,
                              Function<FiscalItemIbsCbs, BigDecimal> value, List<FiscalDivergence> divergences) {
        if (total == null) {
            return;
        }
        BigDecimal sum = groups.stream().map(value).map(Money::orZero).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Money.differs(total, sum)) {
            divergences.add(new FiscalDivergence(TOTALS, field, Money.show(total), Money.show(sum), DivergenceSeverity.ERROR,
                    "%s no total da nota (%s) diferente da soma dos itens (%s)".formatted(label, Money.show(total), Money.show(sum))));
        }
    }
}
