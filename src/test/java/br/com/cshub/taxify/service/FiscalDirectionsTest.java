package br.com.cshub.taxify.service;

import br.com.cshub.taxify.enumerated.FiscalDirection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FiscalDirectionsTest {

    private static final String COMPANY = "11.594.485/0001-05";

    @Test
    void noteFromASupplierIsAnEntry() {
        boolean own = FiscalDirections.isOwnIssue(COMPANY, "98765432000188", null);

        assertThat(own).isFalse();
        assertThat(FiscalDirections.directionOf(own, "1")).isEqualTo(FiscalDirection.INBOUND);
    }

    @Test
    void ownSaleIsAnExitAndOwnEntryNoteIsAnEntry() {
        boolean own = FiscalDirections.isOwnIssue(COMPANY, "11594485000105", null);

        assertThat(own).isTrue();
        assertThat(FiscalDirections.directionOf(own, "1")).isEqualTo(FiscalDirection.OUTBOUND);
        assertThat(FiscalDirections.directionOf(own, "0")).as("devolução/importação emitida pela empresa").isEqualTo(FiscalDirection.INBOUND);
    }

    @Test
    void issuerMatchedByCpfForIndividuals() {
        assertThat(FiscalDirections.isOwnIssue("123.456.789-01", null, "12345678901")).isTrue();
        assertThat(FiscalDirections.isOwnIssue(null, "11594485000105", null)).isFalse();
    }

    @Test
    void participantFilterMatchesNamesOrDocumentDigits() {
        assertThat(FiscalXmlQueryService.participantPattern("  Acme Ltda ")).isEqualTo("%acme ltda%");
        assertThat(FiscalXmlQueryService.participantPattern("11.594.485/0001-05")).isEqualTo("%11594485000105%");
        assertThat(FiscalXmlQueryService.participantPattern(" ")).isNull();
    }
}
