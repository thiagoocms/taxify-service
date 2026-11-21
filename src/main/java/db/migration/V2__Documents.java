package db.migration;

/** CPF/CNPJ de empresas e usuários. */
public class V2__Documents extends TableMigration {

    public V2__Documents() {
        super("documents");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("type", "varchar(10) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("number", "varchar(50) not null");

        // stored as @Enumerated(ORDINAL) before - converted to the constant names
        ordinalEnum("type", "CPF", "RG", "CNH", "CNPJ");
    }
}
