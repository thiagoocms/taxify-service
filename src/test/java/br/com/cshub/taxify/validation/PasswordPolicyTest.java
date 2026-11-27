package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** SN-RN-08: CT-SN-12, CT-SN-13, CT-SN-14, CA-SN-07 e o login com ":" (P-SN-10). */
class PasswordPolicyTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final PasswordPolicy policy = new PasswordPolicy(new PasswordProperties(), encoder, mock(MessageService.class));

    private static String codeOf(Runnable call) {
        try {
            call.run();
        } catch (BadRequestException e) {
            return e.getCode();
        }
        return null;
    }

    @Test
    void requiredTooShortAndTooLong() {
        assertThat(codeOf(() -> policy.assertAcceptable(null))).isEqualTo("error.user.password.required");
        assertThat(codeOf(() -> policy.assertAcceptable("   "))).isEqualTo("error.user.password.required");
        // CT-SN-12: 7 characters
        assertThat(codeOf(() -> policy.assertAcceptable("Abc1234"))).isEqualTo("error.user.password.too.short");
        // CA-SN-07: 8 characters pass
        assertThatCode(() -> policy.assertAcceptable("Abc12345")).doesNotThrowAnyException();
        // CT-SN-13: 37 x "ç" = 74 bytes in UTF-8 (and 37 characters - the byte limit is what refuses it)
        assertThat(codeOf(() -> policy.assertAcceptable("ç".repeat(37)))).isEqualTo("error.user.password.invalid");
        assertThatCode(() -> policy.assertAcceptable("ç".repeat(36))).doesNotThrowAnyException();
    }

    @Test
    void theMinimumCountsCharactersNotBytes() {
        // 8 characters, 16 bytes
        assertThatCode(() -> policy.assertAcceptable("çççççççç")).doesNotThrowAnyException();
    }

    @Test
    void theMinimumComesFromTheConfiguration() {
        PasswordProperties properties = new PasswordProperties();
        properties.setMinLength(10);
        PasswordPolicy longer = new PasswordPolicy(properties, encoder, mock(MessageService.class));
        assertThat(codeOf(() -> longer.assertAcceptable("Abc123456"))).isEqualTo("error.user.password.too.short");
        assertThat(longer.minLength()).isEqualTo(10);
    }

    /** CT-SN-14. */
    @Test
    void aChangeMustBeDifferentFromTheCurrentPassword() {
        String current = encoder.encode("SenhaAtual2026");
        assertThat(codeOf(() -> policy.assertAcceptableChange("SenhaAtual2026", current))).isEqualTo("error.user.password.same");
        assertThatCode(() -> policy.assertAcceptableChange("NovaSenha#2026", current)).doesNotThrowAnyException();
    }

    /** P-SN-10: a password with ":" is fine; a new login with ":" is not. */
    @Test
    void colonsAreAllowedInPasswordsButNotInNewLogins() {
        assertThatCode(() -> policy.assertAcceptable("abc:12345")).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertLoginAllowed("teste.ponto")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.assertLoginAllowed("teste:ponto"))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.login.invalid.char"));
    }
}
