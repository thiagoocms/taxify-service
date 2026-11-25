package br.com.cshub.taxify.enumerated;

/** What a state tax certidão says about the company's debts. */
public enum CertidaoSituacao {
    /** No debts. */
    NEGATIVA("Negativa"),
    /** Debts that block the company (licitação, financiamento...) - an ALTA pendency. */
    POSITIVA("Positiva"),
    /** Debts suspended (parcelamento, impugnação, garantia): valid as a negative one, but worth watching. */
    POSITIVA_EFEITO_NEGATIVA("Positiva com efeito de negativa");

    public final String label;

    CertidaoSituacao(String label) {
        this.label = label;
    }
}
