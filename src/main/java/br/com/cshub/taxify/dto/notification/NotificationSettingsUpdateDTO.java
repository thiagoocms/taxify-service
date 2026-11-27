package br.com.cshub.taxify.dto.notification;

/** {@code PUT /users/me/notification-settings}: {@code {pendencyHighDigest: true|false}} (obrigatório). */
public record NotificationSettingsUpdateDTO(Boolean pendencyHighDigest) {
}
