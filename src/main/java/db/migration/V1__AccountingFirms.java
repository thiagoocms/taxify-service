package db.migration;

/** Escritórios de contabilidade (o tenant). */
public class V1__AccountingFirms extends TableMigration {

    public V1__AccountingFirms() {
        super("accounting_firms");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("billing_provider", "varchar(30)");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("name", "varchar(150) not null");
    }
}
