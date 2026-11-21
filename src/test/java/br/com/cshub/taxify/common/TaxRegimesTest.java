package br.com.cshub.taxify.common;

import br.com.cshub.taxify.enumerated.BusinessActivity;
import br.com.cshub.taxify.enumerated.TaxRegime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaxRegimesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Simples Nacional - Comércio e Serviço | SIMPLES_NACIONAL | COMMERCE_AND_SERVICE",
            "Simples Nacional - Serviço            | SIMPLES_NACIONAL | SERVICE",
            "Lucro Presumido - Comércio            | LUCRO_PRESUMIDO  | COMMERCE",
            "Lucro Real - Comércio - Filial        | LUCRO_REAL       | COMMERCE",
            "LUCRO REAL - COMERCIO E SERVICO       | LUCRO_REAL       | COMMERCE_AND_SERVICE",
            "MEI                                   | MEI              | ",
            "Imune                                 | OTHER            | ",
    })
    void parsesTheLabelsTheFirmUses(String label, TaxRegime regime, BusinessActivity activity) {
        TaxRegimes.Parsed parsed = TaxRegimes.parse(label);

        assertThat(parsed.regime()).isEqualTo(regime);
        assertThat(parsed.activity()).isEqualTo(activity);
    }

    @Test
    void blankIsNoRegime() {
        assertThat(TaxRegimes.parse("  ")).isNull();
        assertThat(TaxRegimes.parse(null)).isNull();
    }

    @Test
    void labelRoundTrips() {
        assertThat(TaxRegimes.label(TaxRegime.SIMPLES_NACIONAL, BusinessActivity.COMMERCE_AND_SERVICE))
                .isEqualTo("Simples Nacional - Comércio e Serviço");
        assertThat(TaxRegimes.label(TaxRegime.MEI, BusinessActivity.SERVICE)).isEqualTo("MEI");
        assertThat(TaxRegimes.label(null, null)).isNull();
    }
}
