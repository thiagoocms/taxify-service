package br.com.cshub.taxify.enumerated;

import br.com.cshub.taxify.domain.auth.Auth;
import br.com.cshub.taxify.domain.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccessProfileTest {

    @Test
    void adminIsASpecialTypeWithEveryPermission() {
        assertThat(AccessProfile.permissionsOf(UserProfileEnum.ADMIN, Set.of()))
                .containsExactlyInAnyOrder(Permission.values());
    }

    @Test
    void employeePermissionsAreTheUnionOfTheirProfiles() {
        Set<Permission> permissions = AccessProfile.permissionsOf(UserProfileEnum.EMPLOYEE,
                EnumSet.of(AccessProfile.FISCAL, AccessProfile.FINANCIAL));

        assertThat(permissions).contains(Permission.INVOICES_SYNC, Permission.FISCAL_RULES_MANAGE,
                Permission.SUBSCRIPTIONS_MANAGE, Permission.BILLING_VIEW);
    }

    @Test
    void employeeWithoutProfilesCanDoNothing() {
        assertThat(AccessProfile.permissionsOf(UserProfileEnum.EMPLOYEE, Set.of())).isEmpty();
        assertThat(AccessProfile.permissionsOf(UserProfileEnum.USER, null)).isEmpty();
    }

    @Test
    void profilesStayInTheirLane() {
        assertThat(AccessProfile.FISCAL.permissions()).doesNotContain(Permission.BILLING_VIEW, Permission.SUBSCRIPTIONS_VIEW);
        assertThat(AccessProfile.FINANCIAL.permissions()).doesNotContain(Permission.INVOICES_VIEW, Permission.FISCAL_RULES_MANAGE);
        assertThat(AccessProfile.MANAGEMENT.permissions())
                .as("Gestão enxerga tudo sem alterar nada")
                .noneMatch(p -> p.name().endsWith("_MANAGE") || p.name().endsWith("_SYNC")
                        || p.name().endsWith("_RECTIFY") || p.name().endsWith("_AUDIT"));
        for (AccessProfile profile : AccessProfile.values()) {
            assertThat(profile.permissions()).as(profile + " precisa ver a carteira").contains(Permission.COMPANIES_VIEW);
        }
    }

    @Test
    void tokenAuthoritiesCarryTheUserTypeAndEveryPermission() {
        User user = new User();
        user.setProfile(UserProfileEnum.EMPLOYEE);
        user.setAccessProfiles(EnumSet.of(AccessProfile.ACCOUNTING));

        List<String> authorities = new Auth(user).getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

        assertThat(authorities).containsExactlyInAnyOrder("EMPLOYEE", "COMPANIES_VIEW", "INVOICES_VIEW", "FISCAL_RULES_VIEW");
    }
}
