package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
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

/** F1-RN-04: admin da plataforma = perfil ADMIN + login na lista + escritório PLATFORM_FIRM_ID. */
class PlatformAdminServiceTest {

    private static final UUID CS_HUB = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID OTHER_FIRM = UUID.fromString("00000000-0000-0000-0000-0000000000f9");

    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final PlatformAdminService service = new PlatformAdminService(List.of(" CsAdmin ", ""), CS_HUB.toString(), currentUser);

    private User user(String login, UserProfileEnum profile, UUID firmId) {
        User user = new User(UUID.randomUUID());
        user.setLogin(login);
        user.setProfile(profile);
        user.setAccountingFirm(new AccountingFirm(firmId));
        return user;
    }

    @Test
    void onlyTheListedAdminsOfTheCsHubFirmArePlatformAdmins() {
        assertThat(service.isPlatformAdmin("csadmin", UserProfileEnum.ADMIN, CS_HUB)).isTrue();
        assertThat(service.isPlatformAdmin("CSADMIN", UserProfileEnum.ADMIN, CS_HUB)).isTrue();
        assertThat(service.isPlatformAdmin("outro", UserProfileEnum.ADMIN, CS_HUB)).isFalse();
        assertThat(service.isPlatformAdmin("csadmin", UserProfileEnum.USER, CS_HUB)).isFalse();
    }

    /** CT1-12 / CA1-06: o mesmo login num escritório que não é o PLATFORM_FIRM_ID nunca vira admin. */
    @Test
    void theSameLoginInAnotherFirmIsNotPlatformAdmin() {
        assertThat(service.isPlatformAdmin("csadmin", UserProfileEnum.ADMIN, OTHER_FIRM)).isFalse();
        assertThat(service.isPlatformAdmin(user("csadmin", UserProfileEnum.ADMIN, OTHER_FIRM))).isFalse();
        assertThat(service.isPlatformAdmin(user("csadmin", UserProfileEnum.ADMIN, CS_HUB))).isTrue();
    }

    @Test
    void anotherFirmCannotChangeRulesEveryFirmUses() {
        when(currentUser.getCurrentUser()).thenReturn(Optional.of(user("csadmin", UserProfileEnum.ADMIN, OTHER_FIRM)));
        assertThatThrownBy(service::assertCanManageGlobalRules).isInstanceOf(AccessDeniedException.class);

        when(currentUser.getCurrentUser()).thenReturn(Optional.of(user("csadmin", UserProfileEnum.ADMIN, CS_HUB)));
        service.assertCanManageGlobalRules();
    }

    /** CA1-06: sem a variável no .env, ninguém é admin da plataforma (e nenhum escritório é isento). */
    @Test
    void withoutTheListOrTheFirmNobodyIsPlatformAdmin() {
        PlatformAdminService noLogins = new PlatformAdminService(List.of(), CS_HUB.toString(), currentUser);
        PlatformAdminService noFirm = new PlatformAdminService(List.of("csadmin"), "", currentUser);
        assertThat(noLogins.isPlatformAdmin("csadmin", UserProfileEnum.ADMIN, CS_HUB)).isFalse();
        assertThat(noFirm.isPlatformAdmin("csadmin", UserProfileEnum.ADMIN, CS_HUB)).isFalse();
        assertThat(noFirm.isPlatformFirm(CS_HUB)).isFalse();
        assertThat(noFirm.platformFirmId()).isEmpty();
    }

    /** CT1-11: o login da lista é reservado no cadastro público (a resposta é a de login em uso). */
    @Test
    void listedLoginsAreReserved() {
        assertThat(service.isReservedLogin(" CSADMIN")).isTrue();
        assertThat(service.isReservedLogin("fiscal")).isFalse();
        assertThat(service.isPlatformFirm(CS_HUB)).isTrue();
        assertThat(service.isPlatformFirm(OTHER_FIRM)).isFalse();
    }
}
