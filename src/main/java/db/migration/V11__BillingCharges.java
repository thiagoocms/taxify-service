package db.migration;

/** Cobranças/honorários sincronizados do provedor de billing. */
public class V11__BillingCharges extends TableMigration {

    public V11__BillingCharges() {
        super("billing_charges");

        column("id", "varchar(36) not null");
        column("amount", "decimal(15,2) not null");
        column("competence_date", "date");
        column("deleted", "bit not null");
        column("due_date", "date not null");
        column("open_amount", "decimal(15,2)");
        column("paid_amount", "decimal(15,2)");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("last_synced_at", "datetime(6)");
        column("source", "varchar(30) not null");
        column("status", "varchar(30) not null");
        column("company_id", "varchar(36) not null");
        column("subscription_id", "varchar(36)");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("external_id", "varchar(100)");
        column("description", "varchar(500)");

        index("idx_billing_charge_company_due", "company_id", "due_date");
        index("idx_billing_charge_external", "source", "external_id");
        foreignKey("fk_billing_charges_company", "company_id", "companies");
        foreignKey("fk_billing_charges_subscription", "subscription_id", "subscriptions");
    }
}
