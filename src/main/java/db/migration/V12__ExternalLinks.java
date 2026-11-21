package db.migration;

/** Vínculo empresa ↔ cadastro no provedor externo. */
public class V12__ExternalLinks extends TableMigration {

    public V12__ExternalLinks() {
        super("external_links");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("provider", "varchar(30) not null");
        column("company_id", "varchar(36) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("external_id", "varchar(100) not null");
        column("external_name", "varchar(255)");

        unique("uk_external_link_company_provider", "company_id", "provider");
        foreignKey("fk_external_links_company", "company_id", "companies");
    }
}
