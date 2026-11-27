package br.com.cshub.taxify.service.notification;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.DuplicateKeys;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.PendencyAlertProperties;
import br.com.cshub.taxify.domain.notification.UserNotificationSetting;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.notification.NotificationSettingsDTO;
import br.com.cshub.taxify.dto.notification.NotificationSettingsUpdateDTO;
import br.com.cshub.taxify.dto.notification.PendencyHighDigestSettingDTO;
import br.com.cshub.taxify.enumerated.NotificationType;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.UnauthorizedException;
import br.com.cshub.taxify.repository.UserNotificationSettingRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Os avisos por e-mail do próprio usuário (AV-RN-08): cada um liga ou desliga o seu; o ADMIN não mexe no de outro na
 * v1. Sem registro vale {@code TAXIFY_PENDENCY_ALERT_DEFAULT_ON}. Desligar e religar não manda o que passou.
 */
@Service
public class NotificationSettingsService {

    private final UserNotificationSettingRepository repository;
    private final CurrentUserService currentUserService;
    private final PendencyAlertProperties properties;
    private final MessageService messageService;
    private final TransactionTemplate transaction;

    public NotificationSettingsService(UserNotificationSettingRepository repository, CurrentUserService currentUserService,
                                       PendencyAlertProperties properties, MessageService messageService,
                                       PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.properties = properties;
        this.messageService = messageService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** AV-RN-02: quem vê o painel de pendências (INVOICES_VIEW; o ADMIN tem todas). */
    public static boolean eligibleForPendencyDigest(User user) {
        return user.getPermissions().contains(Permission.INVOICES_VIEW);
    }

    public NotificationSettingsDTO current() {
        User user = currentUser();
        boolean enabled = repository.findByUserIdAndTypeAndDeletedIsFalse(user.getId(), NotificationType.PENDENCY_HIGH_DIGEST)
                .map(UserNotificationSetting::isEnabled)
                .orElse(properties.isDefaultOn());
        return dto(user, enabled);
    }

    /** 400 {@code error.notification.settings.invalid} sem o valor; grava mesmo sem elegibilidade (vale se ganhar acesso). */
    public NotificationSettingsDTO update(NotificationSettingsUpdateDTO request) {
        if (request == null || request.pendencyHighDigest() == null) {
            throw new BadRequestException("error.notification.settings.invalid",
                    messageService.getMessage("error.notification.settings.invalid"));
        }
        User user = currentUser();
        boolean enabled = request.pendencyHighDigest();
        try {
            save(user.getId(), enabled);
        } catch (DataIntegrityViolationException e) {
            // two tabs at the same time created the row: write again over the one that won
            if (!DuplicateKeys.isDuplicateKey(e)) {
                throw e;
            }
            save(user.getId(), enabled);
        }
        return dto(user, enabled);
    }

    private void save(UUID userId, boolean enabled) {
        transaction.executeWithoutResult(status -> {
            UserNotificationSetting setting = repository
                    .findByUserIdAndTypeAndDeletedIsFalse(userId, NotificationType.PENDENCY_HIGH_DIGEST)
                    .orElseGet(() -> new UserNotificationSetting(userId, NotificationType.PENDENCY_HIGH_DIGEST, enabled));
            setting.setEnabled(enabled);
            repository.saveAndFlush(setting);
        });
    }

    /** O resumo de cada usuário está ligado? Uma consulta para todos; sem registro vale o padrão. */
    public Map<UUID, Boolean> pendencyDigestEnabled(Collection<UUID> userIds) {
        Map<UUID, Boolean> stored = userIds.isEmpty() ? Map.of()
                : repository.findByType(NotificationType.PENDENCY_HIGH_DIGEST, userIds).stream()
                .collect(Collectors.toMap(UserNotificationSetting::getUserId, UserNotificationSetting::isEnabled, (a, b) -> a));
        return userIds.stream().distinct()
                .collect(Collectors.toMap(id -> id, id -> stored.getOrDefault(id, properties.isDefaultOn())));
    }

    private NotificationSettingsDTO dto(User user, boolean enabled) {
        return new NotificationSettingsDTO(new PendencyHighDigestSettingDTO(enabled, eligibleForPendencyDigest(user)));
    }

    private User currentUser() {
        return currentUserService.getCurrentUser().orElseThrow(() -> new UnauthorizedException("error.auth.session.expired",
                messageService.getMessage("error.auth.session.expired")));
    }
}
