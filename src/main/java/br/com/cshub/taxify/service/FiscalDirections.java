package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import org.springframework.util.StringUtils;

/** Classifies a note for the company that owns it - see {@link FiscalDirection}. */
public final class FiscalDirections {

    /** tpNF: 0 = entrada, 1 = saída - from the issuer's point of view. */
    private static final String TP_NF_ENTRADA = "0";

    private FiscalDirections() {
    }

    /** The company issued the note (its CNPJ/CPF is the emitente's). */
    public static boolean isOwnIssue(String companyDocument, String issuerCnpj, String issuerCpf) {
        String company = DocumentNumbers.onlyDigits(companyDocument);
        if (!StringUtils.hasText(company)) {
            return false;
        }
        return company.equals(DocumentNumbers.onlyDigits(issuerCnpj)) || company.equals(DocumentNumbers.onlyDigits(issuerCpf));
    }

    public static FiscalDirection directionOf(boolean ownIssue, String tipoOperacao) {
        if (!ownIssue) {
            return FiscalDirection.INBOUND;
        }
        return TP_NF_ENTRADA.equals(tipoOperacao) ? FiscalDirection.INBOUND : FiscalDirection.OUTBOUND;
    }
}
