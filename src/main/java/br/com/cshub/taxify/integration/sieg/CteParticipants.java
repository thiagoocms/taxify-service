package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.enumerated.CteTakerRole;

/**
 * The document (CNPJ or CPF, digits as in the XML) of each participant group of a CT-e - {@code emit}, {@code rem},
 * {@code exped}, {@code receb}, {@code dest} and the taker ({@code toma3} points at one of the four, {@code toma4} has
 * its own) - and the names of the groups the document keeps. What the upload needs to know the company's roles in it
 * (spec 3.12, R-b; FD-RN-08). A group missing from the XML is null.
 */
public record CteParticipants(
        Party issuer,
        Party sender,
        Party shipper,
        Party receiver,
        Party recipient,
        Party taker,
        CteTakerRole takerRole
) {

    /** One participant group: document (CNPJ, else CPF), name and UF of its address. */
    public record Party(String cnpj, String cpf, String name, String uf) {

        /** CNPJ, else CPF - null when the group has neither. */
        public String document() {
            return hasText(cnpj) ? cnpj : hasText(cpf) ? cpf : null;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }
}
