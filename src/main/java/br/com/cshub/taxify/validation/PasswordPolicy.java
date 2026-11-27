package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.exception.BadRequestException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * A regra única de senha nova (SN-RN-08), usada no cadastro público, na criação e edição de usuário, na redefinição
 * por e-mail e na troca pelo próprio usuário:
 * <ul>
 *   <li>obrigatória: 400 {@code error.user.password.required};</li>
 *   <li>no máximo 72 bytes em UTF-8 (limite do BCrypt): 400 {@code error.user.password.invalid};</li>
 *   <li>no mínimo {@code TAXIFY_PASSWORD_MIN_LENGTH} caracteres (padrão 8): 400 {@code error.user.password.too.short};</li>
 *   <li>na troca e na redefinição, diferente da atual: 400 {@code error.user.password.same}.</li>
 * </ul>
 * Sem regra de composição. Senhas já gravadas continuam valendo, mesmo curtas: a regra só vale para senha nova. O login
 * novo não pode ter {@code :} ({@link #assertLoginAllowed}), porque o login divide {@code login:senha} no primeiro
 * {@code :} (P-SN-10).
 */
@Component
public class PasswordPolicy {

    public static final int MAX_BYTES = 72;
    public static final String REQUIRED = "error.user.password.required";
    public static final String TOO_LONG = "error.user.password.invalid";
    public static final String TOO_SHORT = "error.user.password.too.short";
    public static final String SAME = "error.user.password.same";
    public static final String LOGIN_INVALID_CHAR = "error.user.login.invalid.char";

    private final PasswordProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final MessageService messageService;

    public PasswordPolicy(PasswordProperties properties, PasswordEncoder passwordEncoder, MessageService messageService) {
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.messageService = messageService;
    }

    public int minLength() {
        return properties.getMinLength();
    }

    /** Obrigatória, até 72 bytes e com o mínimo de caracteres. */
    public void assertAcceptable(String password) {
        if (password == null || password.isBlank()) {
            throw badRequest(REQUIRED);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw badRequest(TOO_LONG);
        }
        if (password.codePointCount(0, password.length()) < properties.getMinLength()) {
            throw new BadRequestException(TOO_SHORT, messageService.getMessage(TOO_SHORT, String.valueOf(properties.getMinLength())));
        }
    }

    /** {@link #assertAcceptable} e diferente da senha atual ({@code currentHash} em BCrypt). */
    public void assertAcceptableChange(String password, String currentHash) {
        assertAcceptable(password);
        if (currentHash != null && passwordEncoder.matches(password, currentHash)) {
            throw badRequest(SAME);
        }
    }

    /** Login novo sem {@code :} (cadastro público, criação de usuário e troca do login) - 400 error.user.login.invalid.char. */
    public void assertLoginAllowed(String login) {
        if (login != null && login.indexOf(':') >= 0) {
            throw badRequest(LOGIN_INVALID_CHAR);
        }
    }

    private BadRequestException badRequest(String code) {
        return new BadRequestException(code, messageService.getMessage(code));
    }
}
