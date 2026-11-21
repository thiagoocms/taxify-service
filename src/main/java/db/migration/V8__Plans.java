package db.migration;

/** Planos de serviço do escritório. */
public class V8__Plans extends TableMigration {

    public V8__Plans() {
        super("plans");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("list_price", "decimal(15,2)");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("billing_cycle", "varchar(20)");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("name", "varchar(150) not null");
        column("description", "varchar(500)");
    }
}
