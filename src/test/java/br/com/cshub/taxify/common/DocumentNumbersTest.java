package br.com.cshub.taxify.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentNumbersTest {

    @Test
    void onlyDigitsStripsTheMask() {
        assertThat(DocumentNumbers.onlyDigits("11.594.485/0001-05")).isEqualTo("11594485000105");
        assertThat(DocumentNumbers.onlyDigits("123.456.789-01")).isEqualTo("12345678901");
        assertThat(DocumentNumbers.onlyDigits(null)).isNull();
    }

    @Test
    void formatMasksCnpjAndCpfWithOrWithoutAnExistingMask() {
        assertThat(DocumentNumbers.format("11594485000105")).isEqualTo("11.594.485/0001-05");
        assertThat(DocumentNumbers.format("11.594.485/0001-05")).isEqualTo("11.594.485/0001-05");
        assertThat(DocumentNumbers.format("12345678901")).isEqualTo("123.456.789-01");
    }

    @Test
    void formatLeavesAnythingElseUnchanged() {
        assertThat(DocumentNumbers.format("PASSPORT-X1")).isEqualTo("PASSPORT-X1");
        assertThat(DocumentNumbers.format("1234")).isEqualTo("1234");
        assertThat(DocumentNumbers.format(null)).isNull();
    }
}
