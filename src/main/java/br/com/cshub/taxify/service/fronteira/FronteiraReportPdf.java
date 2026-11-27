package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.fronteira.FronteiraRule;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs;
import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The Fronteira PE report of a company and month, to keep and to send: the summary with the guide after the
 * adjustments, the notes to ask in revision, every note with the calculation used and the rules behind it - the
 * accountant's own formulas included, with their basis. A working document, never the official extract or DAE.
 */
public final class FronteiraReportPdf {

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Color BRAND = new Color(32, 80, 162);
    private static final Color HEADER_BG = new Color(238, 241, 246);
    private static final Color HIGHLIGHT_BG = new Color(255, 244, 229);
    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD, BRAND);
    private static final Font HEADING = new Font(Font.HELVETICA, 10.5f, Font.BOLD, BRAND);
    private static final Font TEXT = new Font(Font.HELVETICA, 8, Font.NORMAL);
    private static final Font BOLD = new Font(Font.HELVETICA, 8, Font.BOLD);
    private static final Font BIG = new Font(Font.HELVETICA, 12, Font.BOLD);
    private static final Font SMALL = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.DARK_GRAY);

    private FronteiraReportPdf() {
    }

    /** A rule as the report names it, and whether the fiscal team checked it. */
    public record RuleInfo(String label, String legalBasis, boolean reviewed) {
    }

    public static Map<UUID, RuleInfo> rulesInfo(Collection<FronteiraRule> rules) {
        Map<UUID, RuleInfo> info = new LinkedHashMap<>();
        for (FronteiraRule rule : rules) {
            info.put(rule.getId(), new RuleInfo(label(rule), rule.getLegalBasis(), rule.isReviewed()));
        }
        return info;
    }

    static String label(FronteiraRule rule) {
        String scope = scope(rule);
        String name = switch (rule.getKind()) {
            case FORMULA -> "Fórmula própria" + (rule.getDescription() == null ? "" : " - " + rule.getDescription())
                    + (rule.getFormula() == null ? "" : ": " + rule.getFormula());
            case SIMPLES_PERCENTUAL -> "Carga líquida " + percent(rule.getPercentage())
                    + (rule.getDescription() == null ? " (Simples, art. 363-A)" : " - " + rule.getDescription());
            case AGREGACAO -> "Agregação " + percent(rule.getAggregationRate());
            case ALIQUOTA_INTERNA -> "Alíquota interna " + percent(rule.getRate());
            case ST_MVA -> "ST com MVA" + (rule.getDescription() == null ? "" : " - " + rule.getDescription());
        };
        return scope.isEmpty() ? name : name + " (" + scope + ")";
    }

    private static String scope(FronteiraRule rule) {
        List<String> parts = new ArrayList<>();
        if (rule.getNcm() != null && !rule.getNcm().isBlank()) {
            parts.add("NCM " + rule.getNcm());
        }
        if (rule.getProductCode() != null && !rule.getProductCode().isBlank()) {
            parts.add("produto " + rule.getProductCode());
        }
        if (rule.getSupplierCnpj() != null && !rule.getSupplierCnpj().isBlank()) {
            parts.add("fornecedor " + rule.getSupplierCnpj());
        }
        return String.join(", ", parts);
    }

    // ---------------------------------------------------------------- PDF

    public static byte[] pdf(FronteiraDTOs.Report report, String companyDocument, Map<UUID, RuleInfo> rules) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        PdfWriter.getInstance(document, out);
        document.open();
        document.add(new Paragraph("Fronteira PE - ICMS antecipado - " + monthLabel(report.periodo()), TITLE));
        document.add(new Paragraph(report.companyName() + (companyDocument == null ? "" : " - CNPJ " + companyDocument), TEXT));
        FronteiraDTOs.Extract extract = report.extract();
        if (extract == null) {
            document.add(new Paragraph("Nenhum extrato do e-Fisco importado para o mês - o relatório só existe com o extrato.", TEXT));
            footer(document);
            document.close();
            return out.toByteArray();
        }
        document.add(new Paragraph("Extrato nº " + text(extract.numeroExtrato()) + " · IE " + text(extract.inscricaoEstadual())
                + (extract.dataVencimento() == null ? "" : " · vence em " + extract.dataVencimento().format(DAY))
                + (extract.credenciamentoInterno() == null ? "" : " · credenciamento " + extract.credenciamentoInterno())
                + " · gerado em " + LocalDateTime.now().format(DAY_TIME) + " pelo Taxify", SMALL));

        FronteiraDTOs.Summary s = report.summary();
        section(document, "Resumo do mês");
        PdfPTable cards = new PdfPTable(4);
        cards.setWidthPercentage(100);
        card(cards, "Cobrado no extrato", money(s.icmsDevido()), s.notas() + " nota(s) · " + money(s.valorNotas()) + " em notas", null);
        card(cards, "Cálculo do Taxify", money(s.icmsCalculado()), s.confere() + " confere(m) · " + s.semRegra() + " sem regra · "
                + s.pendentes() + " pendente(s)", null);
        card(cards, "Cobrado a mais (pedir revisão)", money(s.cobradoAMais()), s.aMais() + " nota(s)", null);
        card(cards, "Valor da guia ajustada", money(s.valorGuia()), "cobrado − o que vai para revisão", HIGHLIGHT_BG);
        document.add(cards);
        List<String> notes = new ArrayList<>();
        if (s.cobradoAMenos().signum() > 0) {
            notes.add("Cobrado a menos: " + money(s.cobradoAMenos()) + " em " + (s.aMenos() + s.naoCalculou())
                    + " nota(s) - fica como cobrado na guia; é risco de cobrança posterior.");
        }
        if (extract.icmsPago() != null && extract.icmsPago().signum() > 0) {
            notes.add("Já pago no extrato: " + money(extract.icmsPago()) + ".");
        }
        if (s.aConferir() > 0) {
            notes.add(s.aConferir() + " resultado(s) usam regra ainda \"a conferir\" pelo time fiscal.");
        }
        if (s.semRegra() + s.pendentes() > 0) {
            notes.add(s.semRegra() + " nota(s) sem regra e " + s.pendentes() + " pendente(s) (sem o XML completo) ficam pelo valor cobrado.");
        }
        for (String note : notes) {
            document.add(new Paragraph("• " + note, TEXT));
        }

        List<FronteiraDTOs.Line> toRevise = report.lines().stream()
                .filter(line -> line.resultado() == FronteiraDTOs.Result.COBROU_A_MAIS).toList();
        section(document, "Notas para pedir revisão (" + toRevise.size() + ")");
        if (toRevise.isEmpty()) {
            document.add(new Paragraph("Nenhuma nota cobrada acima do cálculo do Taxify.", TEXT));
        } else {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"Registro", "NF", "Fornecedor", "UF", "Cobrado", "Taxify", "A mais", "Cálculo usado"});
            for (FronteiraDTOs.Line line : toRevise) {
                rows.add(new String[]{line.registro(), line.numeroNota(), supplier(line), line.uf(), money(line.icmsDevido()),
                        calcValue(line), money(line.diferenca()), calculation(line, rules)});
            }
            table(document, new float[]{1.6f, 0.9f, 3, 0.5f, 1.1f, 1.1f, 1.1f, 4.2f}, rows, Set.of(4, 5, 6), Set.of());
        }

        section(document, "Todas as notas do extrato (" + report.lines().size() + ")");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Registro", "NF", "Fornecedor", "UF", "Valor NF", "Cobrado", "Taxify", "Diferença", "Resultado", "Cálculo usado"});
        Set<Integer> highlighted = new LinkedHashSet<>();
        for (FronteiraDTOs.Line line : report.lines()) {
            if (line.resultado() == FronteiraDTOs.Result.COBROU_A_MAIS) {
                highlighted.add(rows.size());
            }
            rows.add(new String[]{line.registro(), line.numeroNota(), supplier(line), line.uf(), money(line.valorNota()),
                    money(line.icmsDevido()), calcValue(line), money(line.diferenca()), result(line), calculation(line, rules)});
        }
        table(document, new float[]{1.5f, 0.8f, 2.6f, 0.45f, 1, 1, 1, 1, 1.2f, 3.6f}, rows, Set.of(4, 5, 6, 7), highlighted);

        Map<UUID, RuleInfo> used = usedRules(report, rules);
        if (!used.isEmpty()) {
            section(document, "Regras e fórmulas usadas");
            for (RuleInfo rule : used.values()) {
                Paragraph paragraph = new Paragraph();
                paragraph.add(new Phrase(rule.label() + (rule.reviewed() ? "" : " (a conferir)"), BOLD));
                if (rule.legalBasis() != null && !rule.legalBasis().isBlank()) {
                    paragraph.add(new Phrase(" - " + rule.legalBasis(), TEXT));
                }
                document.add(paragraph);
            }
        }
        footer(document);
        document.close();
        return out.toByteArray();
    }

    private static void footer(Document document) {
        Paragraph footer = new Paragraph("Documento de trabalho do escritório, calculado pelo Taxify a partir do extrato do e-Fisco e das "
                + "notas fiscais importadas. Não substitui o extrato nem o DAE oficiais; pauta/PMPF, frete FOB (CT-e) e alíquotas "
                + "especiais devem ser conferidos à parte.", SMALL);
        footer.setSpacingBefore(12);
        document.add(footer);
    }

    private static void card(PdfPTable table, String label, String value, String sub, Color background) {
        Paragraph content = new Paragraph();
        content.add(new Phrase(label + "\n", SMALL));
        content.add(new Phrase(value + "\n", BIG));
        content.add(new Phrase(sub, SMALL));
        PdfPCell cell = new PdfPCell(content);
        cell.setPadding(6);
        cell.setBorderColor(new Color(221, 225, 234));
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        table.addCell(cell);
    }

    private static void section(Document document, String title) {
        Paragraph paragraph = new Paragraph(title, HEADING);
        paragraph.setSpacingBefore(12);
        paragraph.setSpacingAfter(4);
        document.add(paragraph);
    }

    private static void table(Document document, float[] widths, List<String[]> rows, Set<Integer> rightColumns,
                              Set<Integer> highlightedRows) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            for (int j = 0; j < row.length; j++) {
                PdfPCell cell = new PdfPCell(new Phrase(row[j] == null ? "-" : row[j], i == 0 ? BOLD : TEXT));
                cell.setPadding(3);
                if (i == 0) {
                    cell.setBackgroundColor(HEADER_BG);
                } else if (highlightedRows.contains(i)) {
                    cell.setBackgroundColor(HIGHLIGHT_BG);
                }
                if (i > 0 && rightColumns.contains(j)) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }
        document.add(table);
    }

    // ---------------------------------------------------------------- CSV

    public static byte[] csv(FronteiraDTOs.Report report, String companyDocument, Map<UUID, RuleInfo> rules) {
        StringBuilder csv = new StringBuilder("﻿Empresa;CNPJ;Período;Registro;NF;Data de passagem;UF;CNPJ do fornecedor;Fornecedor;"
                + "Valor da NF;Cobrado no e-Fisco;Cálculo do Taxify;Diferença;Resultado;Cálculo usado;Regras;Regra a conferir\n");
        for (FronteiraDTOs.Line line : report.lines()) {
            FronteiraRuleDTOs.NoteCalc calc = line.calculo();
            csv.append(String.join(";", cell(report.companyName()), cell(companyDocument), report.periodo(),
                    cell(line.registro()), cell(line.numeroNota()), cell(line.dataPassagem()), cell(line.uf()),
                    cell(line.cnpjEmitente()), cell(line.nota() == null ? null : line.nota().emitenteNome()),
                    number(line.valorNota()), number(line.icmsDevido()),
                    calc == null || calc.semRegra() ? "" : number(calc.valor()), number(line.diferenca()),
                    result(line), cell(treatments(line)), cell(ruleLabels(line, rules)),
                    line.conferido() ? "não" : "sim")).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Excel in Portuguese: ";" separates, quotes when needed. */
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replace("\r", " ").replace("\n", " ");
        return clean.contains(";") || clean.contains("\"") ? "\"" + clean.replace("\"", "\"\"") + "\"" : clean;
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    // ---------------------------------------------------------------- lines

    static String supplier(FronteiraDTOs.Line line) {
        String name = line.nota() == null ? null : line.nota().emitenteNome();
        return name == null ? text(line.cnpjEmitente()) : name;
    }

    private static String calcValue(FronteiraDTOs.Line line) {
        FronteiraRuleDTOs.NoteCalc calc = line.calculo();
        return calc == null || calc.semRegra() ? "-" : money(calc.valor());
    }

    private static String calculation(FronteiraDTOs.Line line, Map<UUID, RuleInfo> rules) {
        String treatments = treatments(line);
        String labels = ruleLabels(line, rules);
        if (treatments.isEmpty()) {
            return "-";
        }
        return labels.isEmpty() || labels.equals(treatments) ? treatments : treatments + " · " + labels;
    }

    static String treatments(FronteiraDTOs.Line line) {
        if (line.calculo() == null) {
            return "";
        }
        Set<String> labels = new LinkedHashSet<>();
        line.calculo().itens().forEach(item -> labels.add(item.tratamentoLabel()));
        labels.remove(null);
        return String.join(" + ", labels);
    }

    static String ruleLabels(FronteiraDTOs.Line line, Map<UUID, RuleInfo> rules) {
        if (line.calculo() == null) {
            return "";
        }
        Set<String> labels = new LinkedHashSet<>();
        line.calculo().itens().forEach(item -> item.regras().stream().map(rules::get).filter(Objects::nonNull)
                .filter(rule -> !rule.label().startsWith("Alíquota interna"))
                .forEach(rule -> labels.add(rule.label() + (rule.reviewed() ? "" : " (a conferir)"))));
        return String.join(" | ", labels);
    }

    private static Map<UUID, RuleInfo> usedRules(FronteiraDTOs.Report report, Map<UUID, RuleInfo> rules) {
        Map<UUID, RuleInfo> used = new LinkedHashMap<>();
        for (FronteiraDTOs.Line line : report.lines()) {
            if (line.calculo() != null) {
                line.calculo().itens().forEach(item -> item.regras().forEach(id -> {
                    RuleInfo rule = rules.get(id);
                    if (rule != null) {
                        used.putIfAbsent(id, rule);
                    }
                }));
            }
        }
        return used;
    }

    static String result(FronteiraDTOs.Line line) {
        return switch (line.resultado()) {
            case CONFERE -> "Confere";
            case COBROU_A_MAIS -> "Cobrou a mais";
            case COBROU_A_MENOS -> "Cobrou a menos";
            case NAO_CALCULOU -> "SEFAZ não calculou";
            case SEM_REGRA -> "Sem regra";
            case PENDENTE -> "Pendente";
        };
    }

    // ---------------------------------------------------------------- format

    static String money(BigDecimal value) {
        return value == null ? "-" : NumberFormat.getCurrencyInstance(PT_BR).format(value);
    }

    private static String percent(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString().replace('.', ',') + "%";
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    static String monthLabel(String month) {
        try {
            YearMonth value = YearMonth.parse(month);
            String name = value.getMonth().getDisplayName(TextStyle.FULL, PT_BR);
            return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " de " + value.getYear();
        } catch (RuntimeException e) {
            return month;
        }
    }
}
