package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import br.com.cshub.taxify.domain.xml.FiscalItemIbsCbs;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The IBS/CBS group of one item (Reforma Tributária), when the item has one:
 * <ul>
 *   <li><b>cClassTrib x CST</b> - cClassTrib has 6 digits and starts with the CST;</li>
 *   <li><b>rate of the year</b> - for a taxed CST (000 integral, 200 reduced), pCBS/pIBSUF/
 *       pIBSMun must be the reference rate of the note's year ({@link IbsCbsRate}; no row for
 *       the year, no check). The nominal rate is compared - a reduction goes in gRed;</li>
 *   <li><b>calculation</b> - each value = base x rate x (1 - reduction), within one cent, and
 *       vIBS = vIBSUF + vIBSMun. Skipped when there is deferral or refund (gDif/gDevTrib).</li>
 * </ul>
 */
@Component
public class IbsCbsItemCheck implements FiscalCheck {

    public static final String CLASS_TRIB = "IBSCBS_CLASS_TRIB";
    public static final String RATE = "IBSCBS_RATE";
    public static final String CALCULATION = "IBSCBS_CALCULATION";

    /** CSTs where the full (000) or reduced (200) rate applies - the others have no rate to compare. */
    private static final Set<String> TAXED_CSTS = Set.of("000", "200");

    @Override
    public List<FiscalDivergence> check(FiscalXmlDocumentItem item, FiscalCheckContext context) {
        FiscalItemIbsCbs group = item.getIbsCbs();
        if (group == null) {
            return List.of();
        }
        List<FiscalDivergence> divergences = new ArrayList<>();
        checkClassification(group, divergences);
        if (group.cst() != null && TAXED_CSTS.contains(group.cst().trim())) {
            yearOf(item).flatMap(context::ibsCbsRateFor).ifPresent(rate -> checkRates(group, rate, divergences));
        }
        if (!Boolean.TRUE.equals(group.diferimentoOuDevolucao())) {
            checkCalculation(group, divergences);
        }
        return divergences;
    }

    private void checkClassification(FiscalItemIbsCbs group, List<FiscalDivergence> divergences) {
        String cst = group.cst() == null ? null : group.cst().trim();
        String classTrib = group.classificacaoTributaria() == null ? null : group.classificacaoTributaria().trim();
        if (classTrib == null || !classTrib.matches("\\d{6}")) {
            divergences.add(new FiscalDivergence(CLASS_TRIB, "cClassTrib", classTrib, null, DivergenceSeverity.WARNING,
                    "cClassTrib ausente ou fora do formato de 6 dígitos"));
        } else if (cst != null && !classTrib.startsWith(cst)) {
            divergences.add(new FiscalDivergence(CLASS_TRIB, "cClassTrib", classTrib, cst + "xxx", DivergenceSeverity.WARNING,
                    "cClassTrib %s não pertence ao CST %s do IBS/CBS".formatted(classTrib, cst)));
        }
    }

    private void checkRates(FiscalItemIbsCbs group, IbsCbsRate rate, List<FiscalDivergence> divergences) {
        compareRate("pCBS", "CBS", group.aliquotaCbs(), rate.getAliquotaCbs(), rate.getAno(), divergences);
        compareRate("pIBSUF", "IBS estadual", group.aliquotaIbsUf(), rate.getAliquotaIbsUf(), rate.getAno(), divergences);
        compareRate("pIBSMun", "IBS municipal", group.aliquotaIbsMun(), rate.getAliquotaIbsMun(), rate.getAno(), divergences);
    }

    private void compareRate(String field, String label, BigDecimal found, BigDecimal expected, int year,
                             List<FiscalDivergence> divergences) {
        if (found != null && found.compareTo(expected) != 0) {
            divergences.add(new FiscalDivergence(RATE, field, Money.show(found), Money.show(expected), DivergenceSeverity.ERROR,
                    "Alíquota de %s %s%% diferente da referência de %d (%s%%)"
                            .formatted(label, Money.show(found), year, Money.show(expected))));
        }
    }

    private void checkCalculation(FiscalItemIbsCbs group, List<FiscalDivergence> divergences) {
        BigDecimal base = group.baseCalculo();
        if (base != null) {
            compareValue("vCBS", "CBS", base, group.aliquotaCbs(), group.reducaoAliquotaCbs(), group.valorCbs(), divergences);
            compareValue("vIBSUF", "IBS estadual", base, group.aliquotaIbsUf(), group.reducaoAliquotaIbsUf(), group.valorIbsUf(), divergences);
            compareValue("vIBSMun", "IBS municipal", base, group.aliquotaIbsMun(), group.reducaoAliquotaIbsMun(), group.valorIbsMun(), divergences);
        }
        if (group.valorIbs() != null && group.valorIbsUf() != null && group.valorIbsMun() != null) {
            BigDecimal sum = group.valorIbsUf().add(group.valorIbsMun());
            if (Money.differs(group.valorIbs(), sum)) {
                divergences.add(new FiscalDivergence(CALCULATION, "vIBS", Money.show(group.valorIbs()), Money.show(sum),
                        DivergenceSeverity.ERROR, "vIBS diferente de vIBSUF + vIBSMun"));
            }
        }
    }

    private void compareValue(String field, String label, BigDecimal base, BigDecimal rate, BigDecimal reduction,
                              BigDecimal found, List<FiscalDivergence> divergences) {
        if (rate == null || found == null) {
            return;
        }
        BigDecimal expected = Money.tax(base, rate, reduction);
        if (Money.differs(found, expected)) {
            divergences.add(new FiscalDivergence(CALCULATION, field, Money.show(found), Money.show(expected), DivergenceSeverity.ERROR,
                    "Valor de %s %s diferente de base x alíquota (%s)".formatted(label, Money.show(found), Money.show(expected))));
        }
    }

    private static Optional<Integer> yearOf(FiscalXmlDocumentItem item) {
        return Optional.ofNullable(item.getDocument())
                .map(FiscalXmlDocument::getIssueDate)
                .map(LocalDateTime::getYear);
    }
}
