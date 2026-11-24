package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.fronteira.FronteiraRule;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Resultado;
import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs;
import br.com.cshub.taxify.enumerated.FronteiraRuleKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Fronteira report: the guide after the adjustments, the notes to revise and the accountant's formula behind them. */
class FronteiraReportPdfTest {

    private final UUID formulaId = UUID.randomUUID();

    @Test
    void csvHasEveryNoteWithTheCalculationAndTheFormulaUsed() {
        String csv = new String(FronteiraReportPdf.csv(report(), "11594485000105", rules()), StandardCharsets.UTF_8);

        assertThat(csv).startsWith("﻿Empresa;CNPJ;Período;Registro;NF");
        assertThat(csv.lines()).hasSize(3);
        assertThat(csv).contains("012.6.000000001-0;752080;")
                .contains(";1180,46;1180,47;-0,01;Confere;Carga líquida;")
                .contains(";500,00;300,00;200,00;Cobrou a mais;Fórmula própria;"
                        + "Fórmula própria - Diferencial KDM: partida * (interna - inter) (a conferir);sim");
    }

    @Test
    void pdfIsGeneratedWithAndWithoutTheExtract() {
        byte[] pdf = FronteiraReportPdf.pdf(report(), "11594485000105", rules());
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        FronteiraDTOs.Report empty = new FronteiraDTOs.Report(UUID.randomUUID(), "KDM", "2026-11", null, null, List.of(),
                List.of(), BigDecimal.ZERO, List.of());
        assertThat(new String(FronteiraReportPdf.pdf(empty, null, Map.of()), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void rulesAreNamedAsTheAccountantReadsThem() {
        FronteiraRule carga = new FronteiraRule();
        carga.setKind(FronteiraRuleKind.SIMPLES_PERCENTUAL);
        carga.setPercentual(new BigDecimal("5.50"));
        carga.setDescricao("IND TEC CREDENCIADA");
        assertThat(FronteiraReportPdf.label(carga)).isEqualTo("Carga líquida 5,5% - IND TEC CREDENCIADA");

        FronteiraRule agregacao = new FronteiraRule();
        agregacao.setKind(FronteiraRuleKind.AGREGACAO);
        agregacao.setAgregacao(new BigDecimal("20"));
        agregacao.setNcm("6109");
        assertThat(FronteiraReportPdf.label(agregacao)).isEqualTo("Agregação 20% (NCM 6109)");
    }

    private Map<UUID, FronteiraReportPdf.RuleInfo> rules() {
        return Map.of(formulaId, new FronteiraReportPdf.RuleInfo("Fórmula própria - Diferencial KDM: partida * (interna - inter)",
                "Industrialização: diferencial de alíquota", false));
    }

    private FronteiraDTOs.Report report() {
        FronteiraDTOs.Line confere = line(1, "012.6.000000001-0", "752080", "1180.46", "1180.47", Resultado.CONFERE,
                "CARGA_LIQUIDA", "Carga líquida", List.of(), true);
        FronteiraDTOs.Line aMais = line(2, "012.6.000000002-0", "806396", "500.00", "300.00", Resultado.COBROU_A_MAIS,
                "FORMULA", "Fórmula própria", List.of(formulaId), false);
        FronteiraDTOs.Extract extract = new FronteiraDTOs.Extract("2026-11", "10789028-3", "0394697-51", "KDM", null, null, "SIM",
                "IND TEC CREDENCIADA", LocalDate.of(2026, 12, 28), null, new BigDecimal("1680.46"), BigDecimal.ZERO, null, null,
                null, null, null, "extrato.pdf", null, "admin", true);
        FronteiraDTOs.Summary summary = new FronteiraDTOs.Summary(2, new BigDecimal("30000"), new BigDecimal("1680.46"), null,
                0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0, new BigDecimal("1480.47"), new BigDecimal("200.00"),
                BigDecimal.ZERO, 1, 1, 0, 0, 0, 0, 1, new BigDecimal("1480.46"));
        return new FronteiraDTOs.Report(UUID.randomUUID(), "INDUSTRIA DE CONFECCAO KDM LTDA", "2026-11", extract, summary,
                List.of(confere, aMais), List.of(), BigDecimal.ZERO, List.of());
    }

    private static FronteiraDTOs.Line line(int index, String registro, String numero, String cobrado, String taxify,
                                           Resultado resultado, String tratamento, String label, List<UUID> regras,
                                           boolean conferido) {
        BigDecimal value = new BigDecimal(taxify);
        FronteiraRuleDTOs.ItemCalc item = new FronteiraRuleDTOs.ItemCalc(1, "P1", "Produto", "96071100", "6101", tratamento, label,
                new BigDecimal("1000"), value, value, value, conferido, regras, List.of(), null, Map.of());
        FronteiraRuleDTOs.NoteCalc calc = new FronteiraRuleDTOs.NoteCalc(value, value, value, conferido, false, List.of(item));
        return new FronteiraDTOs.Line(index, registro, "01/11/2026", numero, "SP", "43444348000342", new BigDecimal("15000"),
                new BigDecimal(cobrado), null, null, List.of(), null, resultado, conferido,
                new BigDecimal(cobrado).subtract(value), calc);
    }
}
