package db.migration;

/**
 * Taxify SaaS fase 1 (F1-RN-18): trilha de toda mudança da assinatura do escritório - antes, depois, motivo e quem
 * fez (login, {@code system} ou {@code gateway}). Sem chave estrangeira para o escritório de propósito: o expurgo do
 * cadastro não verificado (F1-RN-03) apaga o escritório e o evento fica, sem dado pessoal.
 */
public class V59__FirmSubscriptionEvents extends TableMigration {

    public V59__FirmSubscriptionEvents() {
        super("firm_subscription_events");

        column("id", "varchar(36) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("type", "varchar(40) not null");
        column("before_json", "text");
        column("after_json", "text");
        column("reason", "varchar(500)");
        column("actor", "varchar(50) not null");
        column("created_date", "datetime(6) not null");

        index("idx_firm_subscription_events_firm", "accounting_firm_id", "created_date");
    }
}
