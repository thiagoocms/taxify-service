package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.repository.PasswordResetTokenRepository;
import br.com.cshub.taxify.repository.PendencyAlertDigestRepository;
import br.com.cshub.taxify.repository.PendencyAlertItemRepository;
import br.com.cshub.taxify.repository.UserNotificationSettingRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Expurgo do cadastro não verificado (F1-RN-03): as linhas das tabelas da spec 2026-11-27 que apontam para o usuário
 * (links de senha, preferências de aviso, itens e resumos de pendências), apagadas fisicamente antes do usuário - na
 * ordem das chaves estrangeiras (itens antes dos resumos). Dentro da transação de quem chama.
 */
@Component
public class UserOwnedRowsPurge {

    private final PasswordResetTokenRepository resetTokens;
    private final UserNotificationSettingRepository notificationSettings;
    private final PendencyAlertItemRepository alertItems;
    private final PendencyAlertDigestRepository alertDigests;

    public UserOwnedRowsPurge(PasswordResetTokenRepository resetTokens, UserNotificationSettingRepository notificationSettings,
                              PendencyAlertItemRepository alertItems, PendencyAlertDigestRepository alertDigests) {
        this.resetTokens = resetTokens;
        this.notificationSettings = notificationSettings;
        this.alertItems = alertItems;
        this.alertDigests = alertDigests;
    }

    public void deleteOf(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        resetTokens.deleteByUserIds(userIds);
        notificationSettings.deleteByUserIds(userIds);
        alertItems.deleteByUserIds(userIds);
        alertDigests.deleteByUserIds(userIds);
    }
}
