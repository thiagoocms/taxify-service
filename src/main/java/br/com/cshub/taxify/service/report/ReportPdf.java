package br.com.cshub.taxify.service.report;

import br.com.cshub.taxify.dto.assessment.AssessmentAlertDTO;
import br.com.cshub.taxify.dto.assessment.MonthRevenueDTO;
import br.com.cshub.taxify.dto.assessment.TaxEstimateDTO;
import br.com.cshub.taxify.dto.report.ReportDTOs;
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
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** The monthly report as a PDF for the client (roadmap M12). */
public final class ReportPdf {

    private static final Color BRAND = new Color(32, 80, 162);
    private static final Color HEADER_BG = new Color(238, 241, 246);
    private static final Font TITLE = new Font(Font.HELVETICA, 15, Font.BOLD, BRAND);
    private static final Font HEADING = new Font(Font.HELVETICA, 10.5f, Font.BOLD, BRAND);
    private static final Font TEXT = new Font(Font.HELVETICA, 8.5f, Font.NORMAL);
    private static final Font BOLD = new Font(Font.HELVETICA, 8.5f, Font.BOLD);
    private static final Font SMALL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, Color.DARK_GRAY);

    private ReportPdf() {
    }

    public static byte[] monthly(ReportDTOs.Monthly report) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(document, out);
        document.open();
        document.add(new Paragraph("Relatório mensal - " + monthLabel(report.competence()), TITLE));
        document.add(new Paragraph(report.companyName() + (report.companyDocument() == null ? "" : " - " + report.companyDocument())
                + (report.regime() == null ? "" : " - " + report.regime()), TEXT));
        document.add(new Paragraph("Gerado em " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                + " pelo Taxify a partir das notas fiscais importadas.", SMALL));

        section(document, "Resumo do mês");
        MonthRevenueDTO revenue = report.revenue();
        table(document, new float[]{3, 2}, List.of(
                row("Faturamento líquido (notas de saída)", money(revenue == null ? null : revenue.net())),
                row("Notas de saída emitidas", String.valueOf(report.invoicesIssued())),
                row("Compras (notas de entrada)", money(report.entries().total()) + " em " + report.entries().notes() + " nota(s)"),
                row("RBT12 (12 meses anteriores)", money(report.rbt12())),
                row("Impostos estimados", money(report.totalTaxes()))), false);

        section(document, "Impostos estimados");
        if (report.taxes() == null || report.taxes().isEmpty()) {
            document.add(new Paragraph("Sem estimativa (regime não informado ou sem receita).", TEXT));
        } else {
            List<String[]> rows = new java.util.ArrayList<>();
            rows.add(new String[]{"Imposto", "Base", "Alíquota", "Valor"});
            for (TaxEstimateDTO tax : report.taxes()) {
                rows.add(new String[]{tax.label(), money(tax.base()), tax.rate() == null ? "-" : tax.rate().stripTrailingZeros().toPlainString() + "%",
                        money(tax.amount())});
            }
            table(document, new float[]{4, 2, 1.2f, 2}, rows, true);
        }

        section(document, "Entradas e saídas por CFOP");
        if (report.cfops().isEmpty()) {
            document.add(new Paragraph("Nenhuma nota no mês.", TEXT));
        } else {
            List<String[]> rows = new java.util.ArrayList<>();
            rows.add(new String[]{"Tipo", "CFOP", "Valor dos itens"});
            for (ReportDTOs.CfopTotal cfop : report.cfops()) {
                rows.add(new String[]{"ENTRADA".equals(cfop.direction()) ? "Entrada" : "Saída", cfop.cfop(), money(cfop.total())});
            }
            table(document, new float[]{1.5f, 1.5f, 3}, rows, true);
        }

        section(document, "Retenções");
        if (report.retentions().isEmpty()) {
            document.add(new Paragraph("Nenhuma retenção no mês.", TEXT));
        } else {
            for (ReportDTOs.RetentionTotal retention : report.retentions()) {
                document.add(new Paragraph(natureLabel(retention.nature()) + ": " + money(retention.total()), TEXT));
            }
        }

        section(document, "Pendências e auditoria");
        long open = report.pendencies().stream().mapToLong(ReportDTOs.PendencyCount::count).sum();
        document.add(new Paragraph(open + " pendência(s) aberta(s)" + (open == 0 ? "" : " ("
                + String.join(", ", report.pendencies().stream().map(p -> p.count() + " " + p.severity().toLowerCase()).toList()) + ")")
                + "; " + report.auditFindings() + " achado(s) da auditoria tipo malha no mês.", TEXT));

        if (report.history() != null && !report.history().isEmpty()) {
            section(document, "Evolução do faturamento (13 meses)");
            List<String[]> rows = new java.util.ArrayList<>();
            rows.add(new String[]{"Mês", "Faturamento líquido"});
            for (MonthRevenueDTO month : report.history()) {
                rows.add(new String[]{monthLabel(month.month()), money(month.net())});
            }
            table(document, new float[]{2, 3}, rows, true);
        }

        if (report.alerts() != null && !report.alerts().isEmpty()) {
            section(document, "Alertas");
            for (AssessmentAlertDTO alert : report.alerts()) {
                document.add(new Paragraph("• " + alert.message(), TEXT));
            }
        }
        document.add(new Paragraph(" "));
        document.add(new Paragraph("Valores estimados a partir das notas fiscais importadas; não consideram folha, receitas sem nota "
                + "nem segregação de ST/monofásico. Confira com o escritório antes de qualquer decisão.", SMALL));
        document.close();
        return out.toByteArray();
    }

    private static void section(Document document, String title) {
        Paragraph paragraph = new Paragraph(title, HEADING);
        paragraph.setSpacingBefore(12);
        paragraph.setSpacingAfter(4);
        document.add(paragraph);
    }

    private static String[] row(String label, String value) {
        return new String[]{label, value};
    }

    private static void table(Document document, float[] widths, List<String[]> rows, boolean header) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            for (int j = 0; j < row.length; j++) {
                boolean isHeader = header && i == 0;
                PdfPCell cell = new PdfPCell(new Phrase(row[j] == null ? "-" : row[j], isHeader ? BOLD : TEXT));
                cell.setPadding(4);
                if (isHeader) {
                    cell.setBackgroundColor(HEADER_BG);
                }
                if (j == row.length - 1 && !isHeader) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }
        document.add(table);
    }

    static String money(BigDecimal value) {
        return value == null ? "-" : NumberFormat.getCurrencyInstance(Locale.of("pt", "BR")).format(value);
    }

    private static String natureLabel(String nature) {
        return switch (nature) {
            case "A_RECOLHER" -> "A recolher";
            case "A_COMPENSAR" -> "A compensar";
            default -> "Informativo (ICMS-ST)";
        };
    }

    static String monthLabel(String month) {
        try {
            java.time.YearMonth value = java.time.YearMonth.parse(month);
            String name = value.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.of("pt", "BR"));
            return Character.toUpperCase(name.charAt(0)) + name.substring(1) + " de " + value.getYear();
        } catch (RuntimeException e) {
            return month;
        }
    }
}
