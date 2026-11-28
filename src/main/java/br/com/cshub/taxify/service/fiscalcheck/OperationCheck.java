package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscaldocument.ItemIcms;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The item against the kind of operation of its note (ide/idDest):
 * <ul>
 *   <li><b>CFOP x destination</b> - internal (idDest 1) uses CFOP 1xxx/5xxx, interstate (2)
 *       2xxx/6xxx, abroad (3) 3xxx/7xxx. A warning: the CFOP group is the usual sign, but the
 *       note's own fields may be the wrong ones;</li>
 *   <li><b>interstate ICMS rate</b> - in an interstate operation the rate is fixed by the
 *       Senate: 4% for imported goods (origin 1, 2, 3, 8 - Res. 13/2012); otherwise 7% from
 *       the South/Southeast (except ES) to the North, Northeast, Center-West and ES, and 12% in
 *       every other case (Res. 22/1989). Only CSTs with a rate (00/10/20/70/90) and a known UF
 *       on both sides.</li>
 * </ul>
 */
@Component
public class OperationCheck implements FiscalCheck {

    public static final String CFOP_DESTINATION = "CFOP_DESTINATION";
    public static final String INTERSTATE_RATE = "INTERSTATE_RATE";

    private static final Map<String, Set<Character>> CFOP_GROUPS = Map.of(
            "1", Set.of('1', '5'),
            "2", Set.of('2', '6'),
            "3", Set.of('3', '7'));
    private static final Map<String, String> EXPECTED_GROUPS = Map.of("1", "1xxx/5xxx", "2", "2xxx/6xxx", "3", "3xxx/7xxx");
    private static final Map<String, String> DESTINATION_LABEL = Map.of("1", "interna", "2", "interestadual", "3", "com o exterior");

    private static final Set<String> SOUTH_SOUTHEAST_EXCEPT_ES = Set.of("SP", "RJ", "MG", "PR", "SC", "RS");
    private static final Set<String> NORTH_NORTHEAST_CENTER_WEST_AND_ES = Set.of(
            "AC", "AP", "AM", "PA", "RO", "RR", "TO",
            "AL", "BA", "CE", "MA", "PB", "PE", "PI", "RN", "SE",
            "DF", "GO", "MT", "MS", "ES");
    private static final Set<String> IMPORTED_ORIGINS = Set.of("1", "2", "3", "8");
    private static final Set<String> CSTS_WITH_RATE = Set.of("00", "10", "20", "70", "90");

    static final BigDecimal FOUR = new BigDecimal("4");
    static final BigDecimal SEVEN = new BigDecimal("7");
    static final BigDecimal TWELVE = new BigDecimal("12");

    @Override
    public List<FiscalDivergence> check(FiscalDocumentItem item, FiscalCheckContext context) {
        GoodsInvoice document = item.getDocument();
        String destination = document == null ? null : trim(document.getHeader().destinationType());
        if (destination == null || !CFOP_GROUPS.containsKey(destination)) {
            return List.of();
        }
        List<FiscalDivergence> divergences = new ArrayList<>();
        String cfop = trim(item.cfop());
        if (cfop != null && !cfop.isEmpty() && !CFOP_GROUPS.get(destination).contains(cfop.charAt(0))) {
            divergences.add(new FiscalDivergence(CFOP_DESTINATION, "CFOP", cfop, EXPECTED_GROUPS.get(destination),
                    DivergenceSeverity.WARNING,
                    "CFOP %s não combina com operação %s (idDest %s)".formatted(cfop, DESTINATION_LABEL.get(destination), destination)));
        }
        if ("2".equals(destination)) {
            interstateRate(item, document).ifPresent(divergences::add);
        }
        return divergences;
    }

    private java.util.Optional<FiscalDivergence> interstateRate(FiscalDocumentItem item, GoodsInvoice document) {
        ItemIcms icms = item.getIcms();
        String cst = trim(icms.cst());
        BigDecimal rate = icms.rate();
        if (!item.hasTaxDetail() || icms.csosn() != null || cst == null || !CSTS_WITH_RATE.contains(cst)
                || rate == null || rate.signum() == 0) {
            return java.util.Optional.empty();
        }
        BigDecimal expected = expectedInterstateRate(icms.origin(), trim(document.getIssuer().uf()), trim(document.getRecipient().uf()));
        if (expected == null || rate.compareTo(expected) == 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new FiscalDivergence(INTERSTATE_RATE, "pICMS", Money.show(rate), Money.show(expected),
                DivergenceSeverity.ERROR,
                "Alíquota interestadual de ICMS %s%% - de %s para %s com origem %s deveria ser %s%%".formatted(
                        Money.show(rate), document.getIssuer().uf(), document.getRecipient().uf(),
                        icms.origin(), Money.show(expected))));
    }

    /** Null when it can't be told (UF or origin unknown, same UF on both sides). */
    public static BigDecimal expectedInterstateRate(String origin, String fromUf, String toUf) {
        if (origin == null || fromUf == null || toUf == null || fromUf.equals(toUf)) {
            return null;
        }
        if (IMPORTED_ORIGINS.contains(origin)) {
            return FOUR;
        }
        return SOUTH_SOUTHEAST_EXCEPT_ES.contains(fromUf) && NORTH_NORTHEAST_CENTER_WEST_AND_ES.contains(toUf) ? SEVEN : TWELVE;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
