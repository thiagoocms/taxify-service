package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformAdminServiceTest {

    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final PlatformAdminService service = new PlatformAdminService(List.of(" Admin ", ""), currentUser);

    private User user(String login, UserProfileEnum profile) {
        User user = new User(UUID.randomUUID());
        user.setLogin(login);
        user.setProfile(profile);
        return user;
    }

    @Test
    void onlyTheListedAdminsOfTheCsHubManageTheGlobalRules() {
        assertThat(service.isPlatformAdmin("admin", UserProfileEnum.ADMIN)).isTrue();
        assertThat(service.isPlatformAdmin("ADMIN", UserProfileEnum.ADMIN)).isTrue();
        // admin of another firm, or the listed login without the ADMIN profile
        assertThat(service.isPlatformAdmin("admin.outro", UserProfileEnum.ADMIN)).isFalse();
        assertThat(service.isPlatformAdmin("admin", UserProfileEnum.USER)).isFalse();
    }

    @Test
    void anotherFirmCannotChangeRulesEveryFirmUses() {
        when(currentUser.getCurrentUser()).thenReturn(Optional.of(user("fiscal.escritorio", UserProfileEnum.ADMIN)));
        assertThatThrownBy(service::assertCanManageGlobalRules).isInstanceOf(AccessDeniedException.class);

        when(currentUser.getCurrentUser()).thenReturn(Optional.of(user("admin", UserProfileEnum.ADMIN)));
        service.assertCanManageGlobalRules();
    }

    @Test
    void anEmptyListLetsNobodyChangeThem() {
        PlatformAdminService none = new PlatformAdminService(List.of(), currentUser);
        assertThat(none.isPlatformAdmin("admin", UserProfileEnum.ADMIN)).isFalse();
    }
}
