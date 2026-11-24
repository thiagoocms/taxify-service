package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "Testar fórmula": the result with the note item's own values, or with sample ones. */
class FronteiraRuleServiceTest {

    private final FronteiraRuleService service = new FronteiraRuleService(null, null, null, null);

    @Test
    void withTheNoteItemsValues() {
        // NF 485: R$ 7.728,00 from RN
        FronteiraRuleDTOs.FormulaCheck check = service.check("partida * (interna - inter)",
                Map.of("partida", new BigDecimal("7728.00"), "interna", new BigDecimal("0.205"), "inter", new BigDecimal("0.12"),
                        "nao_existe", BigDecimal.TEN));

        assertThat(check.ok()).isTrue();
        assertThat(check.exemplo()).isFalse();
        assertThat(check.resultado()).isEqualByComparingTo("656.88");
        assertThat(check.valores()).doesNotContainKey("nao_existe").containsKey("percentual");
    }

    @Test
    void withSampleValues() {
        FronteiraRuleDTOs.FormulaCheck check = service.check("partida * 5,5%", null);

        assertThat(check.ok()).isTrue();
        assertThat(check.exemplo()).isTrue();
        assertThat(check.resultado()).isEqualByComparingTo("55.00");
        assertThat(check.variables()).containsKeys("partida", "credito", "credito_sn");
    }

    @Test
    void anInvalidFormulaSaysWhy() {
        FronteiraRuleDTOs.FormulaCheck check = service.check("partida * ", null);

        assertThat(check.ok()).isFalse();
        assertThat(check.message()).isNotBlank();
        assertThat(check.resultado()).isNull();
    }
}
