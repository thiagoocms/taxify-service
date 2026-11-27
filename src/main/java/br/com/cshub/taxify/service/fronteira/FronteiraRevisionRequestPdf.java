package br.com.cshub.taxify.service.fronteira;

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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The draft of the request to SEFAZ-PE to revise the ICMS antecipado of the notes e-Fisco charged beyond Taxify's
 * calculation (COBROU_A_MAIS): who asks, which extract, the notes with the calculation and the rule's own basis, and
 * room to sign. A draft for the accountant to check and file through e-Fisco - never a filed request. The legal basis
 * is only what the rules carry: nothing is added that the rule doesn't bring.
 */
public final class FronteiraRevisionRequestPdf {

    /** Where the name of e-Fisco's revision service goes - the accountant fills it in. */
    static final String SERVICE_PLACEHOLDER = "[confira no e-Fisco o serviço de revisão do extrato de antecipação]";
    static final String DRAFT_NOTE = "Rascunho gerado pelo Taxify - confira os valores e o enquadramento antes de protocolar.";
    static final String UNCHECKED_WARNING = "Há regra(s) ainda \"a conferir\" pelo time fiscal no cálculo de %d nota(s) "
            + "(marcadas abaixo) - confira o enquadramento delas antes de protocolar.";
    static final String NO_BASIS = "[fundamento não informado na regra - informe antes de protocolar]";

    private static final Locale PT_BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", PT_BR);
    private static final Color BRAND = new Color(32, 80, 162);
    private static final Color HEADER_BG = new Color(238, 241, 246);
    private static final Color WARNING_BG = new Color(255, 244, 229);
    private static final Color MEMORY_BG = new Color(248, 249, 251);
    private static final Font TITLE = new Font(Font.HELVETICA, 14, Font.BOLD, BRAND);
    private static final Font HEADING = new Font(Font.HELVETICA, 10.5f, Font.BOLD, BRAND);
    private static final Font TEXT = new Font(Font.HELVETICA, 8.5f, Font.NORMAL);
    private static final Font BOLD = new Font(Font.HELVETICA, 8.5f, Font.BOLD);
    private static final Font CELL = new Font(Font.HELVETICA, 7.5f, Font.NORMAL);
    private static final Font CELL_BOLD = new Font(Font.HELVETICA, 7.5f, Font.BOLD);
    private static final Font WARNING = new Font(Font.HELVETICA, 8.5f, Font.BOLD, new Color(150, 75, 0));
    private static final Font SMALL = new Font(Font.HELVETICA, 7, Font.NORMAL, Color.DARK_GRAY);

    private FronteiraRevisionRequestPdf() {
    }

    /** Who asks for the revision, from the company's record (any of it may be missing). */
    public record Taxpayer(String legalName, String cnpj, String stateRegistration, String cidade) {
    }

    /** One note to revise, with what the draft says about it. */
    public record Item(FronteiraDTOs.Line line, String cenario, List<String> legalBases, boolean ruleToReview,
                       List<String> memoria) {
    }

    /** The draft's content - the same for the PDF and the text. */
    public record Draft(String legalName, String cnpj, String stateRegistration, String cidade, String period,
                        String extractNumber, String revenueCode, LocalDate dueOn, BigDecimal extractAmount,
                        List<Item> itens, BigDecimal chargedOnInvoices, BigDecimal dueOnInvoices, BigDecimal contestado,
                        BigDecimal extractAfterRevision, int toReview, LocalDate data) {
    }

    /** The extract has notes charged beyond Taxify's calculation - otherwise there is nothing to ask. */
    public static boolean hasItems(FronteiraDTOs.Report report) {
        return report.extract() != null && report.lines().stream().anyMatch(FronteiraRevisionRequestPdf::toRevise);
    }

    private static boolean toRevise(FronteiraDTOs.Line line) {
        return line.resultado() == FronteiraDTOs.Result.COBROU_A_MAIS && line.diferenca() != null
                && line.diferenca().signum() > 0;
    }

    public static Draft draft(FronteiraDTOs.Report report, Taxpayer taxpayer, Map<UUID, FronteiraReportPdf.RuleInfo> rules,
                              LocalDate today) {
        if (!hasItems(report)) {
            throw new IllegalStateException("No note charged beyond Taxify's calculation");
        }
        FronteiraDTOs.Extract extract = report.extract();
        List<Item> items = new ArrayList<>();
        BigDecimal charged = BigDecimal.ZERO;
        BigDecimal due = BigDecimal.ZERO;
        BigDecimal contested = BigDecimal.ZERO;
        int unchecked = 0;
        for (FronteiraDTOs.Line line : report.lines()) {
            if (!toRevise(line)) {
                continue;
            }
            boolean toReview = !line.conferido();
            unchecked += toReview ? 1 : 0;
            charged = charged.add(line.icmsDevido());
            due = due.add(line.calculo().valor());
            contested = contested.add(line.diferenca());
            items.add(new Item(line, scenario(line, rules), basis(line, rules), toReview, memory(line)));
        }
        BigDecimal extractValue = extract.icmsARecolher() != null ? extract.icmsARecolher()
                : report.summary() == null ? charged : report.summary().icmsDevido();
        String name = firstNonBlank(taxpayer == null ? null : taxpayer.legalName(), extract.razaoSocial(), report.companyName());
        String ie = firstNonBlank(extract.inscricaoEstadual(), taxpayer == null ? null : taxpayer.stateRegistration());
        return new Draft(name, taxpayer == null ? null : cnpj(taxpayer.cnpj()), ie, taxpayer == null ? null : blankToNull(taxpayer.cidade()),
                report.periodo(), extract.numeroExtrato(), extract.codigoReceita(), extract.dataVencimento(), extractValue,
                List.copyOf(items), charged, due, contested, extractValue.subtract(contested), unchecked, today);
    }

    // ---------------------------------------------------------------- PDF

    public static byte[] pdf(Draft d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 30, 30);
        PdfWriter.getInstance(document, out);
        document.open();
        document.add(new Paragraph("Pedido de revisão do ICMS antecipado (Fronteira PE) - RASCUNHO", TITLE));
        document.add(new Paragraph(DRAFT_NOTE, SMALL));
        if (d.toReview() > 0) {
            PdfPTable box = new PdfPTable(1);
            box.setWidthPercentage(100);
            box.setSpacingBefore(6);
            PdfPCell cell = new PdfPCell(new Phrase("Atenção: " + UNCHECKED_WARNING.formatted(d.toReview()), WARNING));
            cell.setBackgroundColor(WARNING_BG);
            cell.setBorderColor(new Color(240, 200, 150));
            cell.setPadding(6);
            box.addCell(cell);
            document.add(box);
        }

        section(document, "Destinatário");
        document.add(new Paragraph("À Secretaria da Fazenda de Pernambuco (SEFAZ-PE), pelo e-Fisco - serviço: " + SERVICE_PLACEHOLDER, TEXT));

        section(document, "Contribuinte e extrato");
        PdfPTable data = new PdfPTable(new float[]{1.3f, 3, 1.3f, 3});
        data.setWidthPercentage(100);
        field(data, "Razão social", d.legalName());
        field(data, "CNPJ", d.cnpj());
        field(data, "Inscrição estadual (PE)", d.stateRegistration());
        field(data, "Extrato nº", d.extractNumber());
        field(data, "Competência", FronteiraReportPdf.monthLabel(d.period()) + " (" + d.period() + ")");
        field(data, "Receita", d.revenueCode() == null ? "ICMS antecipado" : d.revenueCode() + " - ICMS antecipado");
        field(data, "Vencimento", d.dueOn() == null ? null : d.dueOn().format(DAY));
        field(data, "Valor cobrado no extrato", money(d.extractAmount()));
        document.add(data);

        section(document, "Pedido");
        document.add(new Paragraph(request(d), TEXT));
        PdfPTable totals = new PdfPTable(4);
        totals.setWidthPercentage(100);
        totals.setSpacingBefore(6);
        total(totals, "Cobrado nas notas listadas", money(d.chargedOnInvoices()), null);
        total(totals, "Devido pelo cálculo", money(d.dueOnInvoices()), null);
        total(totals, "Valor contestado", money(d.contestado()), WARNING_BG);
        total(totals, "Extrato após a revisão", money(d.extractAfterRevision()), null);
        document.add(totals);

        section(document, "Notas cobradas a mais (" + d.itens().size() + ")");
        float[] widths = {2.9f, 2.2f, 0.4f, 1, 0.9f, 0.9f, 0.9f, 2.6f, 2.5f};
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (String header : new String[]{"Chave / NF (registro)", "Fornecedor", "UF", "Valor da NF", "Cobrado", "Calculado",
                "Diferença", "Cenário / regra usada", "Fundamento da regra"}) {
            PdfPCell cell = new PdfPCell(new Phrase(header, CELL_BOLD));
            cell.setBackgroundColor(HEADER_BG);
            cell.setPadding(3);
            table.addCell(cell);
        }
        for (Item item : d.itens()) {
            FronteiraDTOs.Line line = item.line();
            Color background = item.ruleToReview() ? WARNING_BG : null;
            cell(table, noteRef(line, true), background, false);
            cell(table, supplier(line), background, false);
            cell(table, line.uf(), background, false);
            cell(table, money(line.valorNota()), background, true);
            cell(table, money(line.icmsDevido()), background, true);
            cell(table, money(line.calculo().valor()), background, true);
            cell(table, money(line.diferenca()), background, true);
            cell(table, item.cenario() + (item.ruleToReview() ? "\nREGRA A CONFERIR" : ""), background, false);
            cell(table, item.legalBases().isEmpty() ? NO_BASIS : String.join("\n", item.legalBases()), background, false);
            if (!item.memoria().isEmpty()) {
                PdfPCell memory = new PdfPCell(new Phrase("Memória de cálculo (Taxify): " + String.join("  ·  ", item.memoria()), SMALL));
                memory.setColspan(widths.length);
                memory.setBackgroundColor(MEMORY_BG);
                memory.setPadding(3);
                memory.setPaddingLeft(10);
                table.addCell(memory);
            }
        }
        PdfPCell label = new PdfPCell(new Phrase("Total", CELL_BOLD));
        label.setColspan(4);
        label.setPadding(3);
        label.setBackgroundColor(HEADER_BG);
        table.addCell(label);
        for (BigDecimal value : List.of(d.chargedOnInvoices(), d.dueOnInvoices(), d.contestado())) {
            PdfPCell cell = new PdfPCell(new Phrase(money(value), CELL_BOLD));
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            cell.setPadding(3);
            cell.setBackgroundColor(HEADER_BG);
            table.addCell(cell);
        }
        PdfPCell rest = new PdfPCell(new Phrase(""));
        rest.setColspan(2);
        rest.setBackgroundColor(HEADER_BG);
        table.addCell(rest);
        document.add(table);

        section(document, "Fechamento");
        document.add(new Paragraph(closing(d), TEXT));
        // place, date and signature never split across pages
        PdfPTable signature = new PdfPTable(1);
        signature.setWidthPercentage(100);
        signature.setSpacingBefore(10);
        signature.setKeepTogether(true);
        PdfPCell signatureCell = new PdfPCell(new Phrase(placeAndDate(d) + "\n\n\n\n______________________________________________\n"
                + "Responsável / contador - nome, CPF e CRC", TEXT));
        signatureCell.setBorder(PdfPCell.NO_BORDER);
        signatureCell.setPadding(0);
        signature.addCell(signatureCell);
        document.add(signature);
        Paragraph footer = new Paragraph(DRAFT_NOTE + " Valores calculados pelo Taxify a partir do extrato do e-Fisco e das "
                + "notas fiscais importadas; pauta/PMPF, frete FOB (CT-e) e alíquotas especiais devem ser conferidos à parte.", SMALL);
        footer.setSpacingBefore(14);
        document.add(footer);
        document.close();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- text (to paste in the e-Fisco form)

    public static String text(Draft d) {
        StringBuilder t = new StringBuilder();
        t.append("PEDIDO DE REVISÃO DO ICMS ANTECIPADO (FRONTEIRA PE) - RASCUNHO\n");
        t.append(DRAFT_NOTE).append("\n");
        if (d.toReview() > 0) {
            t.append("ATENÇÃO: ").append(UNCHECKED_WARNING.formatted(d.toReview())).append("\n");
        }
        t.append("\nÀ SEFAZ-PE, pelo e-Fisco - serviço: ").append(SERVICE_PLACEHOLDER).append("\n\n");
        t.append("Contribuinte: ").append(text(d.legalName())).append("\n");
        t.append("CNPJ: ").append(text(d.cnpj())).append(" - Inscrição estadual: ").append(text(d.stateRegistration())).append("\n");
        t.append("Extrato nº ").append(text(d.extractNumber())).append(" - competência ").append(d.period())
                .append(d.revenueCode() == null ? "" : " - receita " + d.revenueCode())
                .append(d.dueOn() == null ? "" : " - vencimento " + d.dueOn().format(DAY))
                .append(" - valor cobrado ").append(money(d.extractAmount())).append("\n\n");
        t.append(request(d)).append("\n\n");
        t.append("Cobrado nas notas listadas: ").append(money(d.chargedOnInvoices())).append("\n");
        t.append("Devido pelo cálculo: ").append(money(d.dueOnInvoices())).append("\n");
        t.append("Valor contestado: ").append(money(d.contestado())).append("\n");
        t.append("Valor do extrato após a revisão: ").append(money(d.extractAfterRevision())).append("\n\n");
        t.append("NOTAS COBRADAS A MAIS (").append(d.itens().size()).append(")\n");
        int index = 1;
        for (Item item : d.itens()) {
            FronteiraDTOs.Line line = item.line();
            t.append("\n").append(index++).append(". ").append(noteRef(line, false)).append(item.ruleToReview() ? " [REGRA A CONFERIR]" : "").append("\n");
            t.append("   Fornecedor: ").append(supplier(line)).append(line.uf() == null ? "" : " (" + line.uf() + ")").append("\n");
            t.append("   Valor da NF ").append(money(line.valorNota())).append(" - cobrado ").append(money(line.icmsDevido()))
                    .append(" - calculado ").append(money(line.calculo().valor())).append(" - diferença ")
                    .append(money(line.diferenca())).append("\n");
            t.append("   Cenário/regra: ").append(item.cenario()).append("\n");
            t.append("   Fundamento: ").append(item.legalBases().isEmpty() ? NO_BASIS : String.join("; ", item.legalBases())).append("\n");
            if (!item.memoria().isEmpty()) {
                t.append("   Memória de cálculo: ").append(String.join(" | ", item.memoria())).append("\n");
            }
        }
        t.append("\n").append(closing(d)).append("\n\n");
        t.append(placeAndDate(d)).append("\n\n");
        t.append("______________________________\nResponsável / contador - nome, CPF e CRC\n");
        return t.toString().replace(' ', ' ');
    }

    public static byte[] textBytes(Draft d) {
        return text(d).getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- content

    private static String request(Draft d) {
        return "O contribuinte acima requer a revisão do ICMS antecipado cobrado no extrato nº " + text(d.extractNumber())
                + ", competência " + FronteiraReportPdf.monthLabel(d.period()) + ", quanto às " + d.itens().size()
                + " nota(s) fiscal(is) listada(s) abaixo, em que o valor cobrado supera o apurado conforme o cenário e a regra "
                + "indicados em cada uma. Valor total contestado: " + money(d.contestado()) + ". Valor que entende devido nessas "
                + "notas: " + money(d.dueOnInvoices()) + " (cobrado: " + money(d.chargedOnInvoices()) + "), o que leva o extrato de "
                + money(d.extractAmount()) + " para " + money(d.extractAfterRevision()) + ".";
    }

    private static String closing(Draft d) {
        return "Diante do exposto, requer a revisão do extrato para excluir o valor cobrado a mais nas notas listadas, com a "
                + "emissão de novo extrato no valor de " + money(d.extractAfterRevision()) + ". Coloca-se à disposição para apresentar "
                + "os documentos fiscais e as memórias de cálculo que se fizerem necessários.";
    }

    private static String placeAndDate(Draft d) {
        String place = d.cidade() == null ? "[cidade]" : d.cidade();
        return place + "/PE, " + d.data().format(LONG_DAY) + ".";
    }

    /** The treatment and the rules of the note - the rule's name as the report shows it. */
    static String scenario(FronteiraDTOs.Line line, Map<UUID, FronteiraReportPdf.RuleInfo> rules) {
        String treatments = FronteiraReportPdf.treatments(line);
        Set<String> labels = new LinkedHashSet<>();
        line.calculo().itens().forEach(item -> item.regras().stream().map(rules::get).filter(Objects::nonNull)
                .forEach(rule -> labels.add(rule.label() + (rule.reviewed() ? "" : " (a conferir)"))));
        String names = String.join(" | ", labels);
        if (treatments.isEmpty()) {
            return names.isEmpty() ? "-" : names;
        }
        // the rule's name already starts with the treatment ("Carga líquida 5,5%...") - don't say it twice
        return names.isEmpty() ? treatments : names.startsWith(treatments) ? names : treatments + " - " + names;
    }

    /** The basis the rules used bring, as written in them - nothing else. */
    static List<String> basis(FronteiraDTOs.Line line, Map<UUID, FronteiraReportPdf.RuleInfo> rules) {
        Set<String> basis = new LinkedHashSet<>();
        line.calculo().itens().forEach(item -> item.regras().stream().map(rules::get).filter(Objects::nonNull)
                .map(FronteiraReportPdf.RuleInfo::legalBasis).filter(f -> f != null && !f.isBlank())
                .forEach(f -> basis.add(f.strip())));
        return List.copyOf(basis);
    }

    /** Taxify's memory of each item, one line per item ("item 1 (NCM ...): partida ...; ..."). */
    static List<String> memory(FronteiraDTOs.Line line) {
        List<String> lines = new ArrayList<>();
        List<FronteiraRuleDTOs.ItemCalc> items = line.calculo().itens();
        for (FronteiraRuleDTOs.ItemCalc item : items) {
            if (item.memoria() == null || item.memoria().isEmpty()) {
                continue;
            }
            String steps = String.join("; ", item.memoria().stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
            String head = "Item " + item.numeroItem() + (item.ncm() == null ? "" : " (NCM " + item.ncm() + ")")
                    + " = " + money(item.valor());
            lines.add(head + ": " + steps);
        }
        return lines;
    }

    private static String noteRef(FronteiraDTOs.Line line, boolean grouped) {
        String key = line.nota() == null ? null : line.nota().chaveAcesso();
        String nf = "NF " + text(line.numeroNota()) + " (registro " + text(line.registro()) + ")";
        if (key == null || key.isBlank()) {
            return nf;
        }
        return (grouped ? key.replaceAll("(.{4})(?!$)", "$1 ") : key) + (grouped ? "\n" : " - ") + nf;
    }

    private static String supplier(FronteiraDTOs.Line line) {
        String name = line.nota() == null ? null : line.nota().emitenteNome();
        String cnpj = cnpj(line.cnpjEmitente());
        if (name == null || name.isBlank()) {
            return text(cnpj);
        }
        return cnpj == null ? name : name + " - CNPJ " + cnpj;
    }

    // ---------------------------------------------------------------- pdf parts

    private static void section(Document document, String title) {
        Paragraph paragraph = new Paragraph(title, HEADING);
        paragraph.setSpacingBefore(10);
        paragraph.setSpacingAfter(4);
        document.add(paragraph);
    }

    private static void field(PdfPTable table, String label, String value) {
        PdfPCell name = new PdfPCell(new Phrase(label, BOLD));
        name.setBackgroundColor(HEADER_BG);
        name.setPadding(4);
        name.setBorderColor(new Color(221, 225, 234));
        table.addCell(name);
        PdfPCell content = new PdfPCell(new Phrase(text(value), TEXT));
        content.setPadding(4);
        content.setBorderColor(new Color(221, 225, 234));
        table.addCell(content);
    }

    private static void total(PdfPTable table, String label, String value, Color background) {
        Paragraph content = new Paragraph();
        content.add(new Phrase(label + "\n", SMALL));
        content.add(new Phrase(value, new Font(Font.HELVETICA, 12, Font.BOLD)));
        PdfPCell cell = new PdfPCell(content);
        cell.setPadding(6);
        cell.setBorderColor(new Color(221, 225, 234));
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        table.addCell(cell);
    }

    private static void cell(PdfPTable table, String value, Color background, boolean right) {
        PdfPCell cell = new PdfPCell(new Phrase(value == null ? "-" : value, CELL));
        cell.setPadding(3);
        if (background != null) {
            cell.setBackgroundColor(background);
        }
        if (right) {
            cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        }
        table.addCell(cell);
    }

    // ---------------------------------------------------------------- format

    private static String money(BigDecimal value) {
        return FronteiraReportPdf.money(value);
    }

    static String cnpj(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() == 14
                ? digits.replaceFirst("(\\d{2})(\\d{3})(\\d{3})(\\d{4})(\\d{2})", "$1.$2.$3/$4-$5")
                : value.strip();
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }
}
