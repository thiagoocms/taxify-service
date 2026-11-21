package br.com.cshub.taxify.enumerated;

/** What the company does, as the firm classifies it next to the regime ("Simples Nacional - Comércio e Serviço"). */
public enum BusinessActivity {

    COMMERCE("Comércio"),
    SERVICE("Serviço"),
    COMMERCE_AND_SERVICE("Comércio e Serviço"),
    INDUSTRY("Indústria");

    private final String label;

    BusinessActivity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
