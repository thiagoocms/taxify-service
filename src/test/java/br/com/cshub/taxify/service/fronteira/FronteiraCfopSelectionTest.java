package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.fiscaldocument.CfopFilter;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Flag;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Result;
import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CFOP cut of the Fronteira (spec 2026-11-27, CF-RN-21 to 27) with the spec's fictitious extract of 11/2026
 * (section 12): L1 6102 confere, L2 6403 cobrou a mais 30,00, L3 without XML, L4 6102 and 6403 cobrou a menos 10,00;
 * entries without a charge U1 6102 (prévia 45,00) and U2 6949 (12,00). The calculation has minimum = maximum.
 */
class FronteiraCfopSelectionTest {

    private static final UUID U1 = UUID.randomUUID();
    private static final UUID U2 = UUID.randomUUID();

    private static FronteiraRuleDTOs.NoteCalc calc(String value) {
        BigDecimal amount = new BigDecimal(value);
        return new FronteiraRuleDTOs.NoteCalc(amount, amount, amount, true, false, List.of());
    }

    private static FronteiraDTOs.Line line(int number, List<String> cfops, String invoice, String charged, String calculated,
                                           Result result) {
        FronteiraDTOs.NoteRef ref = cfops == null ? null : new FronteiraDTOs.NoteRef(UUID.randomUUID(), "Fornecedor Exemplo",
                null, new BigDecimal(invoice), "Venda", cfops, false, null, null);
        FronteiraRuleDTOs.NoteCalc calc = calculated == null ? null : calc(calculated);
        BigDecimal difference = calc == null ? null : new BigDecimal(charged).subtract(calc.valor());
        return new FronteiraDTOs.Line(number, "R" + number, "05/11/2026", String.valueOf(number), "SP", "11222333000181",
                new BigDecimal(invoice), new BigDecimal(charged), null, null, cfops == null ? List.of(Flag.SEM_XML) : List.of(), ref,
                result, true, difference, calc);
    }

    private static final List<FronteiraDTOs.Line> LINES = List.of(
            line(1, List.of("6102"), "1000.00", "100.00", "100.00", Result.CONFERE),
            line(2, List.of("6403"), "800.00", "80.00", "50.00", Result.COBROU_A_MAIS),
            line(3, null, "400.00", "40.00", null, Result.PENDENTE),
            line(4, List.of("6102", "6403"), "900.00", "90.00", "100.00", Result.COBROU_A_MENOS));

    private static final List<FronteiraDTOs.Uncharged> UNCHARGED = List.of(
            new FronteiraDTOs.Uncharged(U1, "91", "Fornecedor Um", "11222333000181", "SP", null, new BigDecimal("500.00"),
                    List.of("6102"), calc("45.00")),
            new FronteiraDTOs.Uncharged(U2, "92", "Fornecedor Dois", "11222333000181", "SP", null, new BigDecimal("100.00"),
                    List.of("6949"), calc("12.00")));

    private static FronteiraDTOs.CfopSelection cut(String... cfops) {
        return FronteiraService.cfopSelection(CfopFilter.of(List.of(cfops)), LINES, UNCHARGED);
    }

    /** CT-30. */
    @Test
    void cfop6102KeepsLines1And4AndU1WithTheSummaryAccountsOfThem() {
        FronteiraDTOs.CfopSelection cut = cut("6102");

        assertThat(cut.cfops()).containsExactly("6102");
        assertThat(cut.matchingLines()).containsExactly(1, 4);
        assertThat(cut.linesWithoutXml()).isEqualTo(1);
        assertThat(cut.matchingUncharged()).containsExactly(U1);
        FronteiraDTOs.CfopSelectionTotals totals = cut.totals();
        assertThat(totals.notes()).isEqualTo(2);
        assertThat(totals.invoicesAmount()).isEqualByComparingTo("1900.00");
        assertThat(totals.icmsCharged()).isEqualByComparingTo("190.00");
        assertThat(totals.icmsCalculated()).isEqualByComparingTo("200.00");
        assertThat(totals.chargedAbove()).isEqualByComparingTo("0.00");
        assertThat(totals.chargedBelow()).isEqualByComparingTo("10.00");
        assertThat(totals.unchargedCount()).isEqualTo(1);
        assertThat(totals.unchargedPreview()).isEqualByComparingTo("45.00");
    }

    /** CT-31. */
    @Test
    void cfop6403KeepsLines2And4() {
        FronteiraDTOs.CfopSelection cut = cut("6403");

        assertThat(cut.matchingLines()).containsExactly(2, 4);
        assertThat(cut.matchingUncharged()).isEmpty();
        FronteiraDTOs.CfopSelectionTotals totals = cut.totals();
        assertThat(totals.notes()).isEqualTo(2);
        assertThat(totals.invoicesAmount()).isEqualByComparingTo("1700.00");
        assertThat(totals.icmsCharged()).isEqualByComparingTo("170.00");
        assertThat(totals.icmsCalculated()).isEqualByComparingTo("150.00");
        assertThat(totals.chargedAbove()).isEqualByComparingTo("30.00");
        assertThat(totals.chargedBelow()).isEqualByComparingTo("10.00");
        assertThat(totals.unchargedCount()).isZero();
        assertThat(totals.unchargedPreview()).isEqualByComparingTo("0.00");
    }

    /** CT-32: L4 has both and counts once; the canonical list is sorted and without the duplicate. */
    @Test
    void bothCfopsCountLine4Once() {
        FronteiraDTOs.CfopSelection cut = cut("6.403", "6102", "6403");

        assertThat(cut.cfops()).containsExactly("6102", "6403");
        assertThat(cut.matchingLines()).containsExactly(1, 2, 4);
        assertThat(cut.totals().notes()).isEqualTo(3);
        assertThat(cut.totals().icmsCharged()).isEqualByComparingTo("270.00");
    }

    /** CT-34: no extract - only the prévia is cut. */
    @Test
    void withoutAnExtractOnlyThePreviaIsCut() {
        FronteiraDTOs.CfopSelection cut = FronteiraService.cfopSelection(CfopFilter.of(List.of("6102")), List.of(), UNCHARGED);

        assertThat(cut.matchingLines()).isEmpty();
        assertThat(cut.totals().notes()).isZero();
        assertThat(cut.linesWithoutXml()).isZero();
        assertThat(cut.matchingUncharged()).containsExactly(U1);
        assertThat(cut.totals().unchargedPreview()).isEqualByComparingTo("45.00");
    }

    /** CF-RN-22: the line without XML never matches, whatever the CFOP. */
    @Test
    void aLineWithoutXmlNeverMatches() {
        FronteiraDTOs.CfopSelection cut = cut("6949");

        assertThat(cut.matchingLines()).isEmpty();
        assertThat(cut.linesWithoutXml()).isEqualTo(1);
        assertThat(cut.matchingUncharged()).containsExactly(U2);
        assertThat(cut.totals().unchargedPreview()).isEqualByComparingTo("12.00");
    }
}
