package db.migration;

/**
 * Taxify SaaS fase 1 (F1-RN-03): os links de verificação do e-mail do cadastro. Só o SHA-256 do token é gravado (o
 * token em claro nunca). {@code resend} separa os reenvios, que contam no limite por hora, do e-mail do cadastro.
 * Horários em America/Recife.
 */
public class V61__EmailVerifications extends TableMigration {

    public V61__EmailVerifications() {
        super("email_verifications");

        column("id", "varchar(36) not null");
        column("user_id", "varchar(36) not null");
        column("token_hash", "varchar(64) not null");
        column("expires_at", "datetime(6) not null");
        column("used_at", "datetime(6)");
        column("resend", "bit not null");
        column("created_date", "datetime(6) not null");

        unique("uk_email_verifications_token", "token_hash");
        index("idx_email_verifications_user", "user_id", "created_date");
        foreignKey("fk_email_verifications_user", "user_id", "users");
    }
}
