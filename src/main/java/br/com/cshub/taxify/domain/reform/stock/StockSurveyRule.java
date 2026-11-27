package br.com.cshub.taxify.domain.reform.stock;

import br.com.cshub.taxify.enumerated.StockSurveyClassification;
import br.com.cshub.taxify.enumerated.TaxRegime;

import java.util.EnumSet;
import java.util.Set;

/**
 * CE-RN-03 (rule confirmed by Tony, P-01), applied in this order - the first that matches wins. Each rule has its
 * classification and its fixed reason text ({@code reform.stock.reason.<RULE>} in the messages).
 */
public enum StockSurveyRule {
    /** 1 - Simples Nacional/MEI registered from Jan/2027. */
    NEXT_YEAR_SIMPLES(StockSurveyClassification.OUT_OF_SCOPE),
    /** 2 - Lucro Presumido in Dec/2026: hypothesis I. */
    PRESUMED_PROFIT(StockSurveyClassification.HYPOTHESIS_I),
    /** 3 - Lucro Real in Dec/2026: hypotheses II and III. */
    REAL_PROFIT(StockSurveyClassification.HYPOTHESIS_II_III),
    /** 4 - Simples Nacional/MEI in Dec/2026. */
    SIMPLES(StockSurveyClassification.OUT_OF_SCOPE),
    /** 5 - no regime, or OTHER. */
    NO_REGIME(StockSurveyClassification.NO_REGIME);

    private static final Set<TaxRegime> SIMPLES_REGIMES = EnumSet.of(TaxRegime.SIMPLES_NACIONAL, TaxRegime.MEI);

    private final StockSurveyClassification classification;

    StockSurveyRule(StockSurveyClassification classification) {
        this.classification = classification;
    }

    public StockSurveyClassification classification() {
        return classification;
    }

    /** The message key of the fixed reason text. */
    public String reasonKey() {
        return "reform.stock.reason." + name();
    }

    /** The rule that matches the regimes of the company. */
    public static StockSurveyRule of(TaxRegime referenceRegime, TaxRegime nextYearRegime) {
        if (nextYearRegime != null && SIMPLES_REGIMES.contains(nextYearRegime)) {
            return NEXT_YEAR_SIMPLES;
        }
        if (referenceRegime == TaxRegime.LUCRO_PRESUMIDO) {
            return PRESUMED_PROFIT;
        }
        if (referenceRegime == TaxRegime.LUCRO_REAL) {
            return REAL_PROFIT;
        }
        if (referenceRegime != null && SIMPLES_REGIMES.contains(referenceRegime)) {
            return SIMPLES;
        }
        return NO_REGIME;
    }

    public static StockSurveyRule of(ReferenceRegime regime) {
        return of(regime.regime(), regime.nextYearRegime());
    }
}
