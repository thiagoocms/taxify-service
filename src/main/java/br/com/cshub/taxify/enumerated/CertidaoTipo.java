package br.com.cshub.taxify.enumerated;

/** The kind of state certidão: Certidão de Regularidade Fiscal or Certidão Negativa de Débitos. */
public enum CertidaoTipo {
    CRF("Certidão de Regularidade Fiscal"),
    CND("Certidão Negativa de Débitos");

    public final String label;

    CertidaoTipo(String label) {
        this.label = label;
    }
}
