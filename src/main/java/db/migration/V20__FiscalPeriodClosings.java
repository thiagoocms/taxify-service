package db.migration;

/** Fechamento fiscal do mês por empresa. */
public class V20__FiscalPeriodClosings extends TableMigration {

    public V20__FiscalPeriodClosings() {
        super("fiscal_period_closings");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("guide_amount", "decimal(19,2)");
        column("guide_due_date", "date");
        column("guide_issued", "bit not null");
        column("net_revenue", "decimal(19,2)");
        column("period", "date not null");
        column("rbt12", "decimal(19,2)");
        column("total_taxes", "decimal(19,2)");
        column("closed_at", "datetime(6)");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("status", "varchar(10) not null");
        column("tax_regime", "varchar(30)");
        column("company_id", "varchar(36) not null");
        column("closed_by", "varchar(50)");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("notes", "varchar(1000)");
        column("snapshot", "TEXT");

        unique("uk_fiscal_period_closing", "company_id", "period");
        foreignKey("fk_fiscal_period_closings_company", "company_id", "companies");
    }
}
