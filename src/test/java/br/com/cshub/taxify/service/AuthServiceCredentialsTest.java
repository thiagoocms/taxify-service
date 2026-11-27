package br.com.cshub.taxify.service;

import br.com.cshub.taxify.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SN-RN-08 (P-SN-10), CA-SN-17 / CT-SN-28: login:senha is split on the first ":" only. */
class AuthServiceCredentialsTest {

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aPasswordWithColonsIsKeptWhole() {
        assertThat(AuthService.splitCredentials(b64("teste.ponto:abc:12345"))).containsExactly("teste.ponto", "abc:12345");
        assertThat(AuthService.splitCredentials(b64("ana:senha:com:varios:"))).containsExactly("ana", "senha:com:varios:");
    }

    @Test
    void theUsualCaseIsUnchanged() {
        assertThat(AuthService.splitCredentials(b64("ana.fiscal:Senha2026"))).containsExactly("ana.fiscal", "Senha2026");
        assertThat(AuthService.splitCredentials(b64("ana.fiscal:"))).containsExactly("ana.fiscal", "");
        assertThat(AuthService.splitCredentials(b64("sem-dois-pontos"))).hasSize(1);
    }

    @Test
    void accentsAreReadAsUtf8() {
        assertThat(AuthService.splitCredentials(b64("joão:çççççççç"))).containsExactly("joão", "çççççççç");
    }

    @Test
    void notBase64IsA400() {
        assertThatThrownBy(() -> AuthService.splitCredentials("%%%")).isInstanceOf(BadRequestException.class);
    }
}
