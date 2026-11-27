package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.notification.NotificationSettingsDTO;
import br.com.cshub.taxify.dto.notification.NotificationSettingsUpdateDTO;
import br.com.cshub.taxify.dto.password.PasswordChangeRequestDTO;
import br.com.cshub.taxify.dto.password.PasswordChangeResponseDTO;
import br.com.cshub.taxify.security.AllowedWhenBlocked;
import br.com.cshub.taxify.service.notification.NotificationSettingsService;
import br.com.cshub.taxify.service.password.PasswordChangeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Minha conta" do usuário logado (qualquer perfil, sem permissão específica): a troca da própria senha (SN-RN-09) e
 * os avisos por e-mail (AV-RN-08). Liberado no escritório bloqueado: senha é credencial e o aviso é preferência
 * pessoal, não dado do escritório (P-SN-05, AV-RN-08).
 */
@AllowedWhenBlocked
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/users/me")
public class MyAccountResource {

    private final PasswordChangeService passwordChangeService;
    private final NotificationSettingsService notificationSettingsService;

    public MyAccountResource(PasswordChangeService passwordChangeService, NotificationSettingsService notificationSettingsService) {
        this.passwordChangeService = passwordChangeService;
        this.notificationSettingsService = notificationSettingsService;
    }

    /** 200 {@code {token}} (o JWT novo desta sessão; as outras caem); 400 senha atual errada ou regra; 429 limite de erros. */
    @PutMapping("/password")
    public ResponseEntity<PasswordChangeResponseDTO> changePassword(@RequestBody(required = false) PasswordChangeRequestDTO request) {
        return ResponseEntity.ok(passwordChangeService.changeOwn(request == null ? null : request.currentPassword(),
                request == null ? null : request.newPassword()));
    }

    /** {@code {pendencyHighDigest: {enabled, eligible}}}. */
    @GetMapping("/notification-settings")
    public ResponseEntity<NotificationSettingsDTO> notificationSettings() {
        return ResponseEntity.ok(notificationSettingsService.current());
    }

    /** {@code {pendencyHighDigest: true|false}} → o mesmo corpo do GET. */
    @PutMapping("/notification-settings")
    public ResponseEntity<NotificationSettingsDTO> updateNotificationSettings(
            @RequestBody(required = false) NotificationSettingsUpdateDTO request) {
        return ResponseEntity.ok(notificationSettingsService.update(request));
    }
}
