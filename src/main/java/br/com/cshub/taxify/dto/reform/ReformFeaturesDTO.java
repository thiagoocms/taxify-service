package br.com.cshub.taxify.dto.reform;

import java.time.LocalDate;

/**
 * What the "Reforma Tributária" menu shows (CE-RN-15). The other Onda 1 specs add their own fields.
 */
public record ReformFeaturesDTO(StockCredit stockCredit) {

    /** {@code enabled} = TAXIFY_STOCK_SURVEY_ENABLED; {@code closed} from 01/07/2027 (Recife); {@code closesOn} = 2027-06-30. */
    public record StockCredit(boolean enabled, boolean closed, LocalDate closesOn) {
    }
}
