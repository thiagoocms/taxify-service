package br.com.cshub.taxify.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** B-CE-1: formula injection neutralized in every exported text cell (CT-CE-25). */
class CsvCellsTest {

    @Test
    void neutralizesFormulaPrefixes() {
        assertThat(CsvCells.safe("=SOMA(A1) Comércio Ltda")).isEqualTo("'=SOMA(A1) Comércio Ltda");
        assertThat(CsvCells.safe("+55 81")).isEqualTo("'+55 81");
        assertThat(CsvCells.safe("-1+A1")).isEqualTo("'-1+A1");
        // plain numbers are data, never neutralized
        assertThat(CsvCells.safe("-1")).isEqualTo("-1");
        assertThat(CsvCells.safe("+5")).isEqualTo("+5");
        assertThat(CsvCells.safe("-160000,00")).isEqualTo("-160000,00");
        assertThat(CsvCells.safe("@cmd")).isEqualTo("'@cmd");
        assertThat(CsvCells.safe("\tx")).isEqualTo("'\tx");
        assertThat(CsvCells.safe("\rx")).isEqualTo("'\rx");
        assertThat(CsvCells.safe("Comércio Exemplo")).isEqualTo("Comércio Exemplo");
        assertThat(CsvCells.safe(null)).isEmpty();
    }

    @Test
    void quotesWhenTheCellHasTheSeparator() {
        assertThat(CsvCells.cell("Empresa; Filial", ';')).isEqualTo("\"Empresa; Filial\"");
        assertThat(CsvCells.cell("Diz \"oi\"", ';')).isEqualTo("\"Diz \"\"oi\"\"\"");
        assertThat(CsvCells.cell("=1;2", ';')).isEqualTo("\"'=1;2\"");
        assertThat(CsvCells.cell("160000,00", ';')).isEqualTo("160000,00");
    }
}
