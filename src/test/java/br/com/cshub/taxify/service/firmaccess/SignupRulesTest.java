package br.com.cshub.taxify.service.firmaccess;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** F1-RN-02 e F1-RN-11 (CNPJs fictícios). */
class SignupRulesTest {

    /** CA1-04: 11.111.111/0001-11 tem os dígitos verificadores errados; um CNPJ fictício com DV certo passa, com ou sem máscara. */
    @Test
    void cnpjNeedsFourteenDigitsAndValidCheckDigits() {
        assertThat(SignupRules.isValidCnpj("11.111.111/0001-11")).isFalse();
        assertThat(SignupRules.isValidCnpj("11.222.333/0001-81")).isTrue();
        assertThat(SignupRules.isValidCnpj("11222333000181")).isTrue();
        assertThat(SignupRules.isValidCnpj("11222333000262")).isTrue();
        assertThat(SignupRules.isValidCnpj("11222333000182")).isFalse();
        assertThat(SignupRules.isValidCnpj("00000000000000")).isFalse();
        assertThat(SignupRules.isValidCnpj("1122233300018")).isFalse();
        assertThat(SignupRules.isValidCnpj(null)).isFalse();
    }

    /** CA1-05 / CT1-09: "(81) 9 9999-0000" é gravado "81999990000"; "9999-0000" é inválido. */
    @Test
    void phoneIsKeptAsDigitsWithAreaCode() {
        assertThat(SignupRules.normalizePhone("(81) 9 9999-0000")).isEqualTo("81999990000");
        assertThat(SignupRules.normalizePhone("(81) 3333-0000")).isEqualTo("8133330000");
        assertThat(SignupRules.normalizePhone("9999-0000")).isNull();
        assertThat(SignupRules.normalizePhone("")).isNull();
        assertThat(SignupRules.normalizePhone(null)).isNull();
    }

    @Test
    void billingEmailNeedsAnEmailFormatUpTo300() {
        assertThat(SignupRules.isValidEmail("financeiro@escritorio-ficticio.com.br")).isTrue();
        assertThat(SignupRules.isValidEmail("sem-arroba")).isFalse();
        assertThat(SignupRules.isValidEmail("a@" + "b".repeat(300) + ".com")).isFalse();
        assertThat(SignupRules.isValidEmail(null)).isFalse();
    }

    /** CA1-13 / CT1-23: fechado sem a chave, sem versão ou com versão RASCUNHO. */
    @Test
    void signupOpensOnlyWithTheSwitchAndARealTermsVersion() {
        assertThat(SignupRules.signupAvailable(true, "2026-12-01")).isTrue();
        assertThat(SignupRules.signupAvailable(false, "2026-12-01")).isFalse();
        assertThat(SignupRules.signupAvailable(true, "RASCUNHO-1")).isFalse();
        assertThat(SignupRules.signupAvailable(true, "rascunho-2")).isFalse();
        assertThat(SignupRules.signupAvailable(true, "")).isFalse();
        assertThat(SignupRules.signupAvailable(true, null)).isFalse();
    }
}
