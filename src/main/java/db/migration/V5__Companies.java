package db.migration;

/** Empresas clientes do escritório. */
public class V5__Companies extends TableMigration {

    public V5__Companies() {
        super("companies");

        column("id", "varchar(36) not null");
        column("active", "bit");
        column("client_since", "date");
        column("client_until", "date");
        column("deleted", "bit not null");
        column("opening_date", "date");
        column("uf", "varchar(2)");
        column("simples_service_annex", "varchar(5)");
        column("address_city_code", "varchar(7)");
        column("address_zip_code", "varchar(8)");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("main_cnae", "varchar(10)");
        column("acessorias_id", "varchar(20)");
        column("address_number", "varchar(20)");
        column("business_activity", "varchar(30)");
        column("municipal_registration", "varchar(30)");
        column("phone", "varchar(30)");
        column("tax_regime_type", "varchar(30)");
        column("accounting_firm_id", "varchar(36) not null");
        column("document_id", "varchar(36) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("address_city", "varchar(100)");
        column("address_complement", "varchar(100)");
        column("address_district", "varchar(100)");
        column("company_group", "varchar(100)");
        column("tax_regime", "varchar(100)");
        column("name", "varchar(150) not null");
        column("trade_name", "varchar(150)");
        column("address_street", "varchar(200)");
        column("email", "varchar(200)");
        column("secondary_cnaes", "varchar(500)");
        column("state_registration", "varchar(255)");

        unique("uk_companies_document", "document_id");
        foreignKey("fk_companies_accounting_firm", "accounting_firm_id", "accounting_firms");
        foreignKey("fk_companies_document", "document_id", "documents");
    }
}
