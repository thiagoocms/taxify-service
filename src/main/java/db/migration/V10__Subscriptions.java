package db.migration;

/** Assinaturas (contratos) das empresas. */
public class V10__Subscriptions extends TableMigration {

    public V10__Subscriptions() {
        super("subscriptions");

        column("id", "varchar(36) not null");
        column("billing_day", "integer");
        column("deleted", "bit not null");
        column("end_date", "date not null");
        column("fee", "decimal(15,2)");
        column("next_due_date", "date");
        column("start_date", "date not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("last_synced_at", "datetime(6)");
        column("billing_cycle", "varchar(20)");
        column("status", "varchar(20)");
        column("source", "varchar(30)");
        column("company_id", "varchar(36) not null");
        column("plan_id", "varchar(36) not null");
        column("created_by", "varchar(50) not null");
        column("external_contract_number", "varchar(50)");
        column("last_modified_by", "varchar(50)");
        column("external_contract_id", "varchar(100)");
        column("sync_warning", "varchar(500)");

        index("idx_subscription_company_status", "company_id", "status");
        index("idx_subscription_external", "source", "external_contract_id");
        foreignKey("fk_subscriptions_company", "company_id", "companies");
        foreignKey("fk_subscriptions_plan", "plan_id", "plans");
    }
}
