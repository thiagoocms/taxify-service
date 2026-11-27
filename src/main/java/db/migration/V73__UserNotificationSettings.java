package db.migration;

/**
 * Preferências de aviso por e-mail de cada usuário (spec 2026-11-27, AV-RN-08). Hoje só o tipo
 * {@code PENDENCY_HIGH_DIGEST} (resumo diário das pendências ALTA). Sem linha vale o padrão
 * {@code TAXIFY_PENDENCY_ALERT_DEFAULT_ON}.
 */
public class V73__UserNotificationSettings extends TableMigration {

    public V73__UserNotificationSettings() {
        super("user_notification_settings");

        column("id", "varchar(36) not null");
        column("user_id", "varchar(36) not null");
        column("type", "varchar(40) not null");
        column("enabled", "bit not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_user_notification_settings", "user_id", "type");
        foreignKey("fk_user_notification_settings_user", "user_id", "users");
    }
}
