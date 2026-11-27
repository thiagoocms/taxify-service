package br.com.cshub.taxify.domain.notification;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Um aviso ligado ou desligado pelo próprio usuário (AV-RN-08); sem linha vale TAXIFY_PENDENCY_ALERT_DEFAULT_ON. */
@Entity
@Table(name = "user_notification_settings")
public class UserNotificationSetting extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", length = 40, nullable = false, updatable = false)
    private NotificationType type;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    public UserNotificationSetting() {
    }

    public UserNotificationSetting(UUID userId, NotificationType type, boolean enabled) {
        this.userId = userId;
        this.type = type;
        this.enabled = enabled;
        setDeleted(false);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
