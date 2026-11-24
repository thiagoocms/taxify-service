package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantAccessServiceTest {

    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private MessageService messageService;
    @Mock
    private UserCompanyRepository userCompanyRepository;

    private TenantAccessService service;

    @BeforeEach
    void setUp() {
        service = new TenantAccessService(currentUserService, messageService, userCompanyRepository);
    }

    private User user(UserProfileEnum profile) {
        User user = new User(UUID.randomUUID());
        user.setProfile(profile);
        return user;
    }

    @Test
    void adminIsNotRestrictedToAssociatedCompanies() {
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(user(UserProfileEnum.ADMIN)));

        assertThat(service.getRestrictedUserId()).isEmpty();
        assertThat(service.canAccessCompany(UUID.randomUUID())).isTrue();
        verify(userCompanyRepository, never()).existsByUser_IdAndCompany_IdAndDeletedIsFalse(any(), any());
    }

    @Test
    void systemContextIsNotRestricted() {
        when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());

        assertThat(service.canAccessCompany(UUID.randomUUID())).isTrue();
    }

    @Test
    void nonAdminOnlyAccessesAssociatedCompanies() {
        User employee = user(UserProfileEnum.EMPLOYEE);
        UUID associated = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(employee));
        when(userCompanyRepository.existsByUser_IdAndCompany_IdAndDeletedIsFalse(employee.getId(), associated)).thenReturn(true);
        when(userCompanyRepository.existsByUser_IdAndCompany_IdAndDeletedIsFalse(employee.getId(), other)).thenReturn(false);

        assertThat(service.getRestrictedUserId()).contains(employee.getId());
        assertThat(service.canAccessCompany(associated)).isTrue();
        assertThat(service.canAccessCompany(other)).isFalse();
    }

    @Test
    void listsAreFilteredWithOneQueryForTheUsersPortfolio() {
        User user = user(UserProfileEnum.USER);
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID alsoMine = UUID.randomUUID();
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(user));
        when(userCompanyRepository.findCompanyIdsByUser(user.getId())).thenReturn(Set.of(mine, alsoMine));

        assertThat(service.accessibleCompanyIds(List.of(mine, other, alsoMine))).containsExactly(mine, alsoMine);
        assertThat(service.filterAccessible(List.of("a", "b"), s -> s.equals("a") ? mine : other)).containsExactly("a");
        verify(userCompanyRepository, never()).existsByUser_IdAndCompany_IdAndDeletedIsFalse(any(), any());
    }

    @Test
    void adminListsAreNotFiltered() {
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(user(UserProfileEnum.ADMIN)));
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(service.accessibleCompanyIds(List.of(a, b))).containsExactly(a, b);
        verify(userCompanyRepository, never()).findCompanyIdsByUser(any());
    }
}
