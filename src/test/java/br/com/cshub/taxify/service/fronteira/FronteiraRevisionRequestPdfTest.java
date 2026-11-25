package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Resultado;
import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The draft of the revision request: only the notes charged beyond the calculation, with the totals and the rule's basis. */
class FronteiraRevisionRequestPdfTest {

    private static final String KEY = "26261143444348000342550010008063961000000010";

    private final UUID cargaId = UUID.randomUUID();
    private final UUID formulaId = UUID.randomUUID();
    private final FronteiraRevisionRequestPdf.Taxpayer taxpayer = new FronteiraRevisionRequestPdf.Taxpayer(
            "INDUSTRIA EXEMPLO LTDA", "11222333000181", "1234567-89", "Caruaru");

    @Test
    void draftHasOnlyTheNotesChargedBeyondTheCalculationWithTheTotals() {
        FronteiraRevisionRequestPdf.Draft draft = FronteiraRevisionRequestPdf.draft(report(true), taxpayer, rules(), LocalDate.of(2026, 11, 24));

        assertThat(draft.itens()).extracting(item -> item.line().numeroNota()).containsExactly("806396", "806400");
        assertThat(draft.cobradoNasNotas()).isEqualByComparingTo("800.00");
        assertThat(draft.devidoNasNotas()).isEqualByComparingTo("450.00");
        assertThat(draft.contestado()).isEqualByComparingTo("350.00");
        assertThat(draft.valorExtrato()).isEqualByComparingTo("1980.46");
        assertThat(draft.extratoAposRevisao()).isEqualByComparingTo("1630.46");
        assertThat(draft.cnpj()).isEqualTo("11.222.333/0001-81");
        assertThat(draft.inscricaoEstadual()).isEqualTo("1234567-89");
        assertThat(draft.aConferir()).isEqualTo(1);
        FronteiraRevisionRequestPdf.Item carga = draft.itens().get(1);
        assertThat(carga.fundamentos()).containsExactly("Decreto 44.650/2017, art. 363-A");
        assertThat(carga.regraAConferir()).isFalse();
        assertThat(draft.itens().get(0).regraAConferir()).isTrue();
        assertThat(draft.itens().get(0).fundamentos()).isEmpty();
        assertThat(carga.memoria()).singleElement().asString()
                .startsWith("Item 1 (NCM 61091000) = R$")
                .contains("Partida: produto 1.000,00");
    }

    @Test
    void textBringsHeaderRequestNotesBasisAndTheDraftNote() {
        String text = FronteiraRevisionRequestPdf.text(
                FronteiraRevisionRequestPdf.draft(report(true), taxpayer, rules(), LocalDate.of(2026, 11, 24)));

        assertThat(text).contains("À SEFAZ-PE, pelo e-Fisco - serviço: " + FronteiraRevisionRequestPdf.SERVICE_PLACEHOLDER)
                .contains("Contribuinte: INDUSTRIA EXEMPLO LTDA")
                .contains("CNPJ: 11.222.333/0001-81 - Inscrição estadual: 1234567-89")
                .contains("Extrato nº 12345678-9 - competência 2026-11 - receita 058-2 - vencimento 28/12/2026")
                .contains("Valor total contestado: R$ 350,00")
                .contains("Valor que entende devido nessas notas: R$ 450,00")
                .contains("ATENÇÃO: Há regra(s) ainda \"a conferir\"")
                .contains(KEY + " - NF 806396 (registro 012.6.000000002-0) [REGRA A CONFERIR]")
                .contains("Fundamento: " + FronteiraRevisionRequestPdf.NO_BASIS)
                .contains("Fundamento: Decreto 44.650/2017, art. 363-A")
                .contains("Cenário/regra: Carga líquida 5,5% (Simples, art. 363-A)\n")
                .contains("Cenário/regra: Fórmula própria - Diferencial exemplo")
                .contains("Caruaru/PE, 24 de novembro de 2026.")
                .contains(FronteiraRevisionRequestPdf.DRAFT_NOTE)
                .doesNotContain("752080")
                .doesNotContain(" ");
    }

    @Test
    void withEveryRuleCheckedThereIsNoWarning() {
        String text = FronteiraRevisionRequestPdf.text(
                FronteiraRevisionRequestPdf.draft(report(false), taxpayer, rules(), LocalDate.of(2026, 11, 24)));
        assertThat(text).doesNotContain("ATENÇÃO").doesNotContain("[REGRA A CONFERIR]");
    }

    @Test
    void withoutNotesChargedBeyondThereIsNoDraft() {
        FronteiraDTOs.Report report = report(true);
        FronteiraDTOs.Report none = new FronteiraDTOs.Report(report.companyId(), report.companyName(), report.periodo(),
                report.extract(), report.summary(), List.of(report.lines().get(0)), List.of(), BigDecimal.ZERO, List.of());
        assertThat(FronteiraRevisionRequestPdf.hasItems(none)).isFalse();
        assertThat(FronteiraRevisionRequestPdf.hasItems(new FronteiraDTOs.Report(report.companyId(), "EXEMPLO", "2026-11", null, null,
                List.of(), List.of(), BigDecimal.ZERO, List.of()))).isFalse();
        assertThatThrownBy(() -> FronteiraRevisionRequestPdf.draft(none, taxpayer, rules(), LocalDate.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pdfIsGeneratedAndSavedAsExample() throws IOException {
        byte[] pdf = FronteiraRevisionRequestPdf.pdf(
                FronteiraRevisionRequestPdf.draft(report(true), taxpayer, rules(), LocalDate.of(2026, 11, 24)));
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String out = System.getProperty("fronteira.revisao.exemplo");
        if (out != null && !out.isBlank()) {
            Path path = Path.of(out);
            Files.createDirectories(path.getParent());
            Files.write(path, pdf);
        }
    }

    private Map<UUID, FronteiraReportPdf.RuleInfo> rules() {
        return Map.of(
                formulaId, new FronteiraReportPdf.RuleInfo("Fórmula própria - Diferencial exemplo: partida * (interna - inter)", null, false),
                cargaId, new FronteiraReportPdf.RuleInfo("Carga líquida 5,5% (Simples, art. 363-A)", "Decreto 44.650/2017, art. 363-A", true));
    }

    private FronteiraDTOs.Report report(boolean withUncheckedRule) {
        FronteiraDTOs.Line confere = line(1, "012.6.000000001-0", "752080", "1180.46", "1180.47", Resultado.CONFERE,
                "CARGA_LIQUIDA", "Carga líquida", List.of(cargaId), true, null);
        FronteiraDTOs.Line formula = line(2, "012.6.000000002-0", "806396", "500.00", "300.00", Resultado.COBROU_A_MAIS,
                "FORMULA", "Fórmula própria", List.of(withUncheckedRule ? formulaId : cargaId), !withUncheckedRule, KEY);
        FronteiraDTOs.Line carga = line(3, "012.6.000000003-0", "806400", "300.00", "150.00", Resultado.COBROU_A_MAIS,
                "CARGA_LIQUIDA", "Carga líquida", List.of(cargaId), true, null);
        FronteiraDTOs.Extract extract = new FronteiraDTOs.Extract("2026-11", "12345678-9", "1234567-89", "EXEMPLO", null, "058-2", "SIM",
                "IND TEC CREDENCIADA", LocalDate.of(2026, 12, 28), null, new BigDecimal("1980.46"), BigDecimal.ZERO, null, null,
                null, null, null, "extrato.pdf", null, "admin", true);
        FronteiraDTOs.Summary summary = new FronteiraDTOs.Summary(3, new BigDecimal("45000"), new BigDecimal("1980.46"), null,
                0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0, new BigDecimal("1630.47"), new BigDecimal("350.00"),
                BigDecimal.ZERO, 1, 2, 0, 0, 0, 0, withUncheckedRule ? 1 : 0, new BigDecimal("1630.46"));
        return new FronteiraDTOs.Report(UUID.randomUUID(), "INDUSTRIA EXEMPLO LTDA", "2026-11", extract, summary,
                List.of(confere, formula, carga), List.of(), BigDecimal.ZERO, List.of());
    }

    private static FronteiraDTOs.Line line(int index, String registro, String numero, String cobrado, String taxify,
                                           Resultado resultado, String tratamento, String label, List<UUID> regras,
                                           boolean conferido, String chave) {
        BigDecimal value = new BigDecimal(taxify);
        List<String> memoria = List.of("Partida: produto 1.000,00 + IPI 0,00 + frete 0,00 + seguro e outras despesas 0,00 = 1.000,00",
                "Resultado: R$ " + taxify.replace('.', ','));
        FronteiraRuleDTOs.ItemCalc item = new FronteiraRuleDTOs.ItemCalc(1, "P1", "CAMISETA", "61091000", "6101", tratamento, label,
                new BigDecimal("1000"), value, value, value, conferido, regras, memoria, null, Map.of());
        FronteiraRuleDTOs.NoteCalc calc = new FronteiraRuleDTOs.NoteCalc(value, value, value, conferido, false, List.of(item));
        FronteiraDTOs.NoteRef nota = new FronteiraDTOs.NoteRef(UUID.randomUUID(), "MALHARIA EXEMPLO LTDA", LocalDateTime.of(2026, 11, 1, 10, 0),
                new BigDecimal("15000"), "VENDA", List.of("6101"), false, null, chave);
        return new FronteiraDTOs.Line(index, registro, "01/11/2026", numero, "SP", "43444348000342", new BigDecimal("15000"),
                new BigDecimal(cobrado), null, null, List.of(), nota, resultado, conferido,
                new BigDecimal(cobrado).subtract(value), calc);
    }
}
