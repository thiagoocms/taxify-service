package db.migration;

/**
 * Resumo diário das pendências ALTA por e-mail (spec 2026-11-27, AV-RN-09 e AV-RN-10): um por usuário e dia civil de
 * Recife (chave única - é a reserva atômica contra duas instâncias). Situação SENDING (reservado), SENT, FAILED ou
 * CAPPED (acima do teto global). O índice (user_id, sent_at) serve à ordem do teto: quem está há mais tempo sem
 * receber vem primeiro. {@code sent_at} é a hora de Recife.
 */
public class V74__PendencyAlertDigests extends TableMigration {

    public V74__PendencyAlertDigests() {
        super("pendency_alert_digests");

        column("id", "varchar(36) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("user_id", "varchar(36) not null");
        column("reference_date", "date not null");
        column("status", "varchar(10) not null");
        column("companies_count", "int not null default 0");
        column("pendencies_count", "int not null default 0");
        column("sent_at", "datetime(6)");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_pendency_alert_digests_user_day", "user_id", "reference_date");
        index("idx_pendency_alert_digests_user_sent", "user_id", "sent_at");
        index("idx_pendency_alert_digests_day_status", "reference_date", "status");
        foreignKey("fk_pendency_alert_digests_user", "user_id", "users");
        foreignKey("fk_pendency_alert_digests_firm", "accounting_firm_id", "accounting_firms");
    }
}
