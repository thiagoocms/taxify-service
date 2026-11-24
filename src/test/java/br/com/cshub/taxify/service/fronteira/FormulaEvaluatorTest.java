package br.com.cshub.taxify.service.fronteira;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaEvaluatorTest {

    private static final Map<String, BigDecimal> VALUES = Map.of(
            "partida", new BigDecimal("1000"),
            "icms_origem", new BigDecimal("70"),
            "interna", new BigDecimal("0.205"),
            "inter", new BigDecimal("0.07"));

    private static BigDecimal eval(String formula) {
        return FormulaEvaluator.evaluate(formula, VALUES);
    }

    @Test
    void arithmeticPercentAndCommaDecimals() {
        assertThat(eval("partida * 5,5%")).isEqualByComparingTo("55");
        assertThat(eval("partida * 0.055")).isEqualByComparingTo("55");
        assertThat(eval("(partida - icms_origem) / (1 - interna) * interna - icms_origem").setScale(6, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("169.811321");
        assertThat(eval("-partida + 2 * 3")).isEqualByComparingTo("-994");
        assertThat(eval("PARTIDA × 2 ÷ 4")).isEqualByComparingTo("500");
    }

    @Test
    void functions() {
        assertThat(eval("arred(partida / 3)")).isEqualByComparingTo("333.33");
        assertThat(eval("max(partida * 5%; icms_origem)")).isEqualByComparingTo("70");
        assertThat(eval("min(partida * 5%; icms_origem; 10)")).isEqualByComparingTo("10");
    }

    @Test
    void aMissingVariableCountsAsZero() {
        assertThat(eval("partida * percentual")).isEqualByComparingTo("0");
    }

    @Test
    void errorsComeWithAMessageForPeople() {
        assertThatThrownBy(() -> eval("partida * aliquota_x")).hasMessageContaining("não é uma variável conhecida");
        assertThatThrownBy(() -> eval("partida / (interna - interna)")).hasMessageContaining("Divisão por zero");
        assertThatThrownBy(() -> eval("partida * (2")).hasMessageContaining("Faltou \")\"");
        assertThatThrownBy(() -> eval("partida $ 2")).hasMessageContaining("não é aceito");
        assertThatThrownBy(() -> eval("partida 2")).hasMessageContaining("Sobrou");
        assertThatThrownBy(() -> eval("")).hasMessageContaining("vazia");
        assertThatThrownBy(() -> FormulaEvaluator.validate("Runtime.exec(1)")).isInstanceOf(FormulaEvaluator.FormulaException.class);
    }

    @Test
    void variablesUsed() {
        assertThat(FormulaEvaluator.variablesIn("(partida - icms_origem) * interna")).containsExactly("icms_origem", "interna", "partida");
    }
}
