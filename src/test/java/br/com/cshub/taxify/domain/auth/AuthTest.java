package br.com.cshub.taxify.domain.auth;

import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review item 1 (F1-RN-03): whatever the authentication path, a pending firm's user does not authenticate - and the
 * pending state only shows after the right password.
 */
class AuthTest {

    private static Auth auth(boolean pending) {
        User user = new User(UUID.randomUUID());
        user.setLogin("dona.ficticia");
        user.setPassword("segredo");
        user.setProfile(UserProfileEnum.ADMIN);
        return new Auth(user, pending);
    }

    @SuppressWarnings("deprecation")
    private static DaoAuthenticationProvider provider(Auth auth) {
        UserDetailsService users = login -> auth;
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(NoOpPasswordEncoder.getInstance());
        return provider;
    }

    @Test
    void aPendingFirmsUserIsRefusedOnlyAfterTheRightPassword() {
        DaoAuthenticationProvider pending = provider(auth(true));

        assertThatThrownBy(() -> pending.authenticate(new UsernamePasswordAuthenticationToken("dona.ficticia", "errada")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> pending.authenticate(new UsernamePasswordAuthenticationToken("dona.ficticia", "segredo")))
                .isInstanceOf(CredentialsExpiredException.class);
    }

    @Test
    void aVerifiedFirmsUserAuthenticates() {
        assertThat(auth(false).isCredentialsNonExpired()).isTrue();
        assertThat(provider(auth(false)).authenticate(new UsernamePasswordAuthenticationToken("dona.ficticia", "segredo"))
                .isAuthenticated()).isTrue();
    }
}
