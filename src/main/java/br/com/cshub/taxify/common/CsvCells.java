package br.com.cshub.taxify.common;

import java.util.regex.Pattern;

/**
 * Cells of the CSV files the Taxify exports (opened in Excel or LibreOffice):
 * <ul>
 *   <li>{@link #safe} neutralizes formula injection - a text cell starting with {@code =}, {@code +}, {@code -},
 *       {@code @}, a tab or a carriage return gets a leading {@code '} and is shown as text - except a plain number
 *       ({@code -1}, {@code +5}, {@code 160000,00}), which is data;</li>
 *   <li>{@link #cell} also quotes the value when it has the separator, a quote or a line break.</li>
 * </ul>
 * The old CSVs (audit, Fronteira, retentions) don't use it yet (P-CE-03, separate task).
 */
public final class CsvCells {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+([.,]\\d+)?");

    private CsvCells() {
    }

    /** The text with the formula prefix neutralized; null = empty. */
    public static String safe(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (NUMBER.matcher(text).matches()) {
            // a plain number ("-1", "160000,00") is data, not a formula: kept as is so the sheet still reads it
            return text;
        }
        char first = text.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
            return "'" + text;
        }
        return text;
    }

    /** {@link #safe} and quoted when needed for the separator. */
    public static String cell(String text, char separator) {
        String value = safe(text);
        if (value.indexOf(separator) >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
