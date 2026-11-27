package db.migration;

/**
 * Links de redefinição de senha ("Esqueci a senha", spec 2026-11-27, SN-RN-03 e SN-RN-04). Só o SHA-256 do token é
 * gravado (único). {@code requested_at} é a hora do pedido no relógio de America/Recife e é o que contam a validade, os
 * limites por hora e por dia (por conta e por IP) e a limpeza de 48 h; o {@code created_date} da auditoria fica com a
 * hora da JVM (UTC no container), como nas outras tabelas. O IP só existe para o limite por IP e é apagado quando a
 * senha é redefinida (LGPD).
 */
public class V72__PasswordResetTokens extends TableMigration {

    public V72__PasswordResetTokens() {
        super("password_reset_tokens");

        column("id", "varchar(36) not null");
        column("user_id", "varchar(36) not null");
        column("token_hash", "varchar(64) not null");
        column("requested_at", "datetime(6) not null");
        column("expires_at", "datetime(6) not null");
        column("used_at", "datetime(6)");
        column("invalidated_at", "datetime(6)");
        column("requested_ip", "varchar(45)");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_password_reset_tokens_hash", "token_hash");
        index("idx_password_reset_tokens_user", "user_id", "requested_at");
        index("idx_password_reset_tokens_ip", "requested_ip", "requested_at");
        index("idx_password_reset_tokens_requested", "requested_at");
        foreignKey("fk_password_reset_tokens_user", "user_id", "users");
    }
}
