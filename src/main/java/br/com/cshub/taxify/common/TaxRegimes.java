package br.com.cshub.taxify.common;

import br.com.cshub.taxify.enumerated.BusinessActivity;
import br.com.cshub.taxify.enumerated.TaxRegime;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Reads the free-text regime the firm's systems use - Acessórias sends "Simples Nacional -
 * Comércio e Serviço", "Lucro Real - Comércio - Filial", "MEI" - into structured fields, and
 * writes them back as that same kind of label.
 */
public final class TaxRegimes {

    public record Parsed(TaxRegime regime, BusinessActivity activity) {
    }

    private TaxRegimes() {
    }

    /** Null when the text is blank; unknown regimes become OTHER, a missing activity stays null. */
    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = normalize(text);
        return new Parsed(regimeOf(normalized), activityOf(normalized));
    }

    /** "Simples Nacional - Comércio e Serviço" - the label shown and kept next to the structured fields. */
    public static String label(TaxRegime regime, BusinessActivity activity) {
        if (regime == null) {
            return null;
        }
        return activity == null || regime == TaxRegime.MEI ? regime.label() : regime.label() + " - " + activity.label();
    }

    private static TaxRegime regimeOf(String text) {
        if (text.contains("simples")) {
            return TaxRegime.SIMPLES_NACIONAL;
        }
        if (text.contains("presumido")) {
            return TaxRegime.LUCRO_PRESUMIDO;
        }
        if (text.contains("real")) {
            return TaxRegime.LUCRO_REAL;
        }
        if (text.equals("mei") || text.startsWith("mei ") || text.startsWith("mei-") || text.contains("microempreendedor")) {
            return TaxRegime.MEI;
        }
        return TaxRegime.OTHER;
    }

    private static BusinessActivity activityOf(String text) {
        boolean commerce = text.contains("comercio");
        boolean service = text.contains("servico");
        if (commerce && service) {
            return BusinessActivity.COMMERCE_AND_SERVICE;
        }
        if (commerce) {
            return BusinessActivity.COMMERCE;
        }
        if (service) {
            return BusinessActivity.SERVICE;
        }
        if (text.contains("industria")) {
            return BusinessActivity.INDUSTRY;
        }
        return null;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .strip();
    }
}
