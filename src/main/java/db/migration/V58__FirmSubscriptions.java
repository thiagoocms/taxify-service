package db.migration;

/**
 * Taxify SaaS fase 1: a assinatura do escritório ao Taxify (Plano Taxify, que a CS Hub vende ao escritório - não
 * confundir com {@code subscriptions}, o Plano de honorário de cada empresa cliente). Uma linha por escritório, com a
 * situação de acesso (F1-RN-07), as datas do teste e da liberação, o bloqueio e a revisão dos migrados (F1-RN-13).
 */
public class V58__FirmSubscriptions extends TableMigration {

    public V58__FirmSubscriptions() {
        super("firm_subscriptions");

        column("id", "varchar(36) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("platform_plan_id", "varchar(36)");
        column("status", "varchar(30) not null");
        column("trial_ends_on", "date");
        column("access_until", "date");
        column("blocked_since", "date");
        column("blocked_reason", "varchar(30)");
        column("migrated", "bit not null");
        column("reviewed_at", "datetime(6)");
        column("reviewed_by", "varchar(50)");
        column("source", "varchar(20) not null");
        column("gateway_customer_id", "varchar(100)");
        column("gateway_subscription_id", "varchar(100)");
        column("notes", "varchar(500)");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_firm_subscriptions_firm", "accounting_firm_id");
        index("idx_firm_subscriptions_status", "status");
        foreignKey("fk_firm_subscriptions_firm", "accounting_firm_id", "accounting_firms");
    }
}
