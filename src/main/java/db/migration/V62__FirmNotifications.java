package db.migration;

/**
 * Taxify SaaS fase 1 (F1-RN-16): os avisos de fim do teste já enviados. A chave única (escritório, tipo, data de fim
 * do teste) deixa o envio idempotente; um teste estendido tem outra data e ganha avisos novos.
 */
public class V62__FirmNotifications extends TableMigration {

    public V62__FirmNotifications() {
        super("firm_notifications");

        column("id", "varchar(36) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("type", "varchar(20) not null");
        column("reference_date", "date not null");
        column("sent_at", "datetime(6) not null");

        unique("uk_firm_notifications", "accounting_firm_id", "type", "reference_date");
        foreignKey("fk_firm_notifications_firm", "accounting_firm_id", "accounting_firms");
    }
}
