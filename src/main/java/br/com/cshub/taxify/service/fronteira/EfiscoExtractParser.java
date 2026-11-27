package br.com.cshub.taxify.service.fronteira;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the e-Fisco (SEFAZ-PE) "Extrato de notas fiscais relativas a operações interestaduais sujeitas
 * ao ICMS antecipado" PDF: the header (período, IE, credenciamento, vencimento...), one line per note
 * (registro, data de passagem, nº, UF, valor da NF, ICMS devido, CNPJ do emitente) and the footer
 * totals (a recolher, pago, em processo...). The PDF's text comes out one row per line when sorted
 * by position. Labels are matched without their accents - the PDF's encoding of them varies.
 */
@Component
public class EfiscoExtractParser {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private static final String MONEY = "\\d{1,3}(?:\\.\\d{3})*,\\d{2}";
    /** registro · [data de passagem] · nº da nota · UF · valor da NF · ICMS devido · CNPJ/CPF do emitente */
    private static final Pattern NOTE_LINE = Pattern.compile(
            "^([\\d.\\-]{6,})\\s+(?:(\\d{2}/\\d{2}/\\d{4}(?:\\s+\\d{2}:\\d{2}(?::\\d{2})?)?)\\s+)?(\\d+)\\s+([A-Z]{2})\\s+("
                    + MONEY + ")\\s+(" + MONEY + ")\\s+([\\d./\\-]{11,18})\\s*$");
    private static final Pattern MONEY_ONLY_LINE = Pattern.compile("^(?:" + MONEY + ")(?:\\s+" + MONEY + ")*\\s*$");
    private static final Pattern FOOTER_LABEL = Pattern.compile("ICMS\\s+(.+?)(?=\\s+ICMS\\s|$)");

    public record Note(int line, String recordNumber, String crossingDate, String invoiceNumber, String uf,
                       BigDecimal invoiceAmount, BigDecimal icmsDue, String issuerCnpj) {
    }

    public record Extract(YearMonth period, String stateRegistration, String legalName, String are,
                          String revenueCode, String credenciamentoAntecipacao, String credenciamentoInterno,
                          LocalDate dueDate, String extractNumber, LocalDateTime issuedAt,
                          List<Note> notes, Map<String, BigDecimal> totals) {

        public BigDecimal total(String key) {
            return totals.getOrDefault(key, BigDecimal.ZERO);
        }
    }

    /** Footer keys (the label after "ICMS", accent-free, upper case). */
    public static final String A_RECOLHER = "A RECOLHER";
    public static final String PAGO = "PAGO";
    public static final String EM_PROCESSO = "EM PROCESSO";
    public static final String AUTUADO = "AUTUADO";
    public static final String MANDADO_SEGURANCA = "SOB MANDADO DE SEGURANCA";
    public static final String ANISTIADO = "ANISTIADO";
    public static final String REG_DEBITO = "REG. DEB";

    public static class InvalidExtractException extends RuntimeException {
        public InvalidExtractException(String message) {
            super(message);
        }
    }

    public Extract parse(byte[] pdf) {
        String text;
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(document);
        } catch (IOException e) {
            throw new InvalidExtractException("O arquivo não é um PDF legível.");
        }
        return parseText(text);
    }

    Extract parseText(String text) {
        String[] lines = text.replace("\r", "").split("\n");
        if (Plain.of(text).indexOf("ICMS ANTECIPADO") < 0 && Plain.of(text).indexOf("OPERACOES INTERESTADUAIS") < 0) {
            throw new InvalidExtractException("O PDF não parece ser o extrato de notas fiscais do ICMS antecipado do e-Fisco.");
        }

        Map<String, String> header = new LinkedHashMap<>();
        List<Note> notes = new ArrayList<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        List<String> pendingLabels = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher note = NOTE_LINE.matcher(line);
            if (note.matches()) {
                notes.add(new Note(notes.size() + 1, note.group(1), note.group(2), stripZeros(note.group(3)), note.group(4),
                        money(note.group(5)), money(note.group(6)), digits(note.group(7))));
                continue;
            }
            if (pendingLabels != null && MONEY_ONLY_LINE.matcher(line).matches()) {
                String[] values = line.split("\\s+");
                for (int v = 0; v < Math.min(values.length, pendingLabels.size()); v++) {
                    totals.put(pendingLabels.get(v), money(values[v]));
                }
                pendingLabels = null;
                continue;
            }
            String plain = Plain.of(line);
            if (plain.startsWith("ICMS ") && !plain.contains(":")) {
                List<String> labels = new ArrayList<>();
                Matcher label = FOOTER_LABEL.matcher(plain);
                while (label.find()) {
                    labels.add(label.group(1).strip());
                }
                pendingLabels = labels.isEmpty() ? null : labels;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                header.put(Plain.of(line.substring(0, colon)).strip(), line.substring(colon + 1).strip());
            }
        }

        if (notes.isEmpty()) {
            throw new InvalidExtractException("Nenhuma nota encontrada no extrato.");
        }
        YearMonth periodo = periodo(field(header, "PER.{0,2}ODO FISCAL"));
        if (periodo == null) {
            throw new InvalidExtractException("O extrato não traz o período fiscal (mm/aaaa).");
        }
        return new Extract(periodo, field(header, "INSCRI.{0,2}O ESTADUAL"), field(header, "RAZ.?O SOCIAL"),
                field(header, "ARE"), field(header, "C.?DIGO DA RECEITA"),
                field(header, "CREDENCIAMENTO ANTECIPA.{0,2}O"), field(header, "CREDENCIAMENTO INTERNO"),
                date(field(header, "DATA DE VENCIMENTO")), field(header, "N.{0,3}DO EXTRATO"),
                dateTime(field(header, "EMITIDO EM")), List.copyOf(notes), canonicalTotals(totals));
    }

    /** The header value whose (accent-free) label matches {@code labelRegex} as a whole; blank = null. */
    private static String field(Map<String, String> header, String labelRegex) {
        Pattern pattern = Pattern.compile(labelRegex);
        return header.entrySet().stream()
                .filter(entry -> pattern.matcher(entry.getKey()).matches())
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .findFirst().orElse(null);
    }

    private static final Map<String, Pattern> FOOTER_KEYS = Map.of(
            A_RECOLHER, Pattern.compile("A RECOLHER"),
            PAGO, Pattern.compile("PAGO"),
            EM_PROCESSO, Pattern.compile("EM PROCESSO"),
            AUTUADO, Pattern.compile("AUTUADO"),
            MANDADO_SEGURANCA, Pattern.compile("SOB MANDADO DE SEGURAN.?A"),
            ANISTIADO, Pattern.compile("ANISTIADO"),
            REG_DEBITO, Pattern.compile("REG\\.? ?D.?B.*"));

    /** Footer labels as read (accents maybe broken) to the fixed keys above; unknown labels are kept as read. */
    private static Map<String, BigDecimal> canonicalTotals(Map<String, BigDecimal> read) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        read.forEach((label, value) -> totals.put(FOOTER_KEYS.entrySet().stream()
                .filter(entry -> entry.getValue().matcher(label).matches())
                .map(Map.Entry::getKey)
                .findFirst().orElse(label), value));
        return totals;
    }

    private static YearMonth periodo(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(\\d{2})/(\\d{4})").matcher(value);
        return matcher.find() ? YearMonth.of(Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1))) : null;
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\d{2}/\\d{2}/\\d{4}").matcher(value);
        try {
            return matcher.find() ? LocalDate.parse(matcher.group(), DAY) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDateTime dateTime(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}").matcher(value);
        try {
            return matcher.find() ? LocalDateTime.parse(matcher.group(), DAY_TIME) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static BigDecimal money(String value) {
        return new BigDecimal(value.replace(".", "").replace(",", "."));
    }

    static String digits(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }

    /** Note numbers are compared without leading zeros (nNF is stored that way). */
    static String stripZeros(String value) {
        String digits = digits(value);
        return digits == null ? null : digits.replaceFirst("^0+(?=\\d)", "");
    }

    /** Upper case, accents and anything outside A-Z/0-9/basic punctuation dropped - for label matching. */
    static final class Plain {
        static String of(String value) {
            String decomposed = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "")
                    .toUpperCase(Locale.ROOT);
            // A broken accent comes out as U+FFFD or similar - drop it, keep the rest of the word.
            return decomposed.replaceAll("[^A-Z0-9 .,:/()\\-]", "").replaceAll("\\s+", " ");
        }
    }
}
