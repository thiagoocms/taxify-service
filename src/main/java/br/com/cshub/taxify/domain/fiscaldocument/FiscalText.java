package br.com.cshub.taxify.domain.fiscaldocument;

/**
 * The one rule for "trimmed" and "has text" in the fiscal document model, the same as the SQL of the cutover (V87):
 * {@code TRIM(x)} removes only the ordinary space (U+0020) at both ends, and {@code NULLIF(TRIM(x), '') IS NOT NULL} is
 * "has text". Not {@link String#strip()} (Unicode white space), so Java and SQL never disagree on a CFOP, a model or a
 * document.
 */
public final class FiscalText {

    private FiscalText() {
    }

    /** {@code value} without the ordinary spaces at both ends; null stays null. */
    public static String trimSpaces(String value) {
        if (value == null) {
            return null;
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    /** Not null and not only ordinary spaces. */
    public static boolean hasText(String value) {
        return value != null && !trimSpaces(value).isEmpty();
    }
}
