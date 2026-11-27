package db.migration;

/**
 * As pendências já avisadas a cada usuário, uma vez por episódio (spec 2026-11-27, AV-RN-04): o episódio é o
 * {@code pendencies.first_seen_at}, copiado em {@code episode_started_at}, que só muda quando a pendência reabre. A
 * chave única (pendency_id, user_id, episode_started_at) é a deduplicação. {@code digest_id} aponta o resumo que levou
 * a pendência e fica nulo quando o resumo é apagado na limpeza de 365 dias (o item continua valendo enquanto a
 * pendência estiver aberta no mesmo episódio).
 */
public class V75__PendencyAlertItems extends TableMigration {

    public V75__PendencyAlertItems() {
        super("pendency_alert_items");

        column("id", "varchar(36) not null");
        column("digest_id", "varchar(36)");
        column("pendency_id", "varchar(36) not null");
        column("user_id", "varchar(36) not null");
        column("episode_started_at", "datetime(6) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_pendency_alert_items_episode", "pendency_id", "user_id", "episode_started_at");
        index("idx_pendency_alert_items_user", "user_id");
        index("idx_pendency_alert_items_digest", "digest_id");
        foreignKey("fk_pendency_alert_items_digest", "digest_id", "pendency_alert_digests");
        foreignKey("fk_pendency_alert_items_pendency", "pendency_id", "pendencies");
        foreignKey("fk_pendency_alert_items_user", "user_id", "users");
    }
}
