package br.com.cshub.taxify.enumerated;

/** Regime tributário of a client company - what decides how its period is assessed (see TaxAssessmentService). */
public enum TaxRegime {

    MEI("MEI"),
    SIMPLES_NACIONAL("Simples Nacional"),
    LUCRO_PRESUMIDO("Lucro Presumido"),
    LUCRO_REAL("Lucro Real"),
    /** Imune/isenta, produtor rural, pessoa física... - no assessment estimate. */
    OTHER("Outro");

    private final String label;

    TaxRegime(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
