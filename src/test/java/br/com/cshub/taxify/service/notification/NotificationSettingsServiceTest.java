package br.com.cshub.taxify.service.notification;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.PendencyAlertProperties;
import br.com.cshub.taxify.domain.notification.UserNotificationSetting;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.notification.NotificationSettingsDTO;
import br.com.cshub.taxify.dto.notification.NotificationSettingsUpdateDTO;
import br.com.cshub.taxify.enumerated.AccessProfile;
import br.com.cshub.taxify.enumerated.NotificationType;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.UserNotificationSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AV-RN-02 e AV-RN-08: CT-AV-14, CT-AV-15, CT-AV-16 e o "eligible". */
class NotificationSettingsServiceTest {

    private final UserNotificationSettingRepository repository = mock(UserNotificationSettingRepository.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final MessageService messages = mock(MessageService.class);
    private final PendencyAlertProperties properties = new PendencyAlertProperties();
    private NotificationSettingsService service;
    private User ana;

    @BeforeEach
    void setUp() {
        service = new NotificationSettingsService(repository, currentUser, properties, messages, mock(PlatformTransactionManager.class));
        lenient().when(messages.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        ana = new User(UUID.randomUUID());
        ana.setProfile(UserProfileEnum.EMPLOYEE);
        ana.setAccessProfiles(Set.of(AccessProfile.FISCAL));
        lenient().when(currentUser.getCurrentUser()).thenReturn(Optional.of(ana));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void withoutARecordTheDefaultApplies() {
        when(repository.findByUserIdAndTypeAndDeletedIsFalse(ana.getId(), NotificationType.PENDENCY_HIGH_DIGEST)).thenReturn(Optional.empty());
        NotificationSettingsDTO on = service.current();
        assertThat(on.pendencyHighDigest().enabled()).isTrue();
        assertThat(on.pendencyHighDigest().eligible()).isTrue();

        properties.setDefaultOn(false);
        assertThat(service.current().pendencyHighDigest().enabled()).isFalse();
    }

    /** Without INVOICES_VIEW (PAYROLL) the option is not eligible; the ADMIN always is. */
    @Test
    void eligibilityFollowsThePanelPermission() {
        ana.setAccessProfiles(Set.of(AccessProfile.PAYROLL));
        assertThat(service.current().pendencyHighDigest().eligible()).isFalse();
        User admin = new User(UUID.randomUUID());
        admin.setProfile(UserProfileEnum.ADMIN);
        assertThat(NotificationSettingsService.eligibleForPendencyDigest(admin)).isTrue();
    }

    @Test
    void updateStoresTheChoice() {
        UserNotificationSetting stored = new UserNotificationSetting(ana.getId(), NotificationType.PENDENCY_HIGH_DIGEST, true);
        when(repository.findByUserIdAndTypeAndDeletedIsFalse(ana.getId(), NotificationType.PENDENCY_HIGH_DIGEST))
                .thenReturn(Optional.of(stored));
        NotificationSettingsDTO off = service.update(new NotificationSettingsUpdateDTO(false));
        assertThat(off.pendencyHighDigest().enabled()).isFalse();
        assertThat(stored.isEnabled()).isFalse();
    }

    @Test
    void aMissingValueIs400() {
        assertThatThrownBy(() -> service.update(new NotificationSettingsUpdateDTO(null))).isInstanceOfSatisfying(BadRequestException.class,
                e -> assertThat(e.getCode()).isEqualTo("error.notification.settings.invalid"));
        assertThatThrownBy(() -> service.update(null)).isInstanceOf(BadRequestException.class);
    }

    /** One query for many users; the stored choice wins, the rest get the default. */
    @Test
    void manyUsersAtOnce() {
        UUID off = UUID.randomUUID();
        UUID none = UUID.randomUUID();
        when(repository.findByType(any(), any())).thenReturn(List.of(
                new UserNotificationSetting(off, NotificationType.PENDENCY_HIGH_DIGEST, false)));
        Map<UUID, Boolean> result = service.pendencyDigestEnabled(List.of(off, none));
        assertThat(result).containsEntry(off, false).containsEntry(none, true);
    }
}
