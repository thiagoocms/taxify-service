package db.migration;

/** Histórico de regime tributário das empresas. */
public class V7__CompanyTaxRegimes extends TableMigration {

    public V7__CompanyTaxRegimes() {
        super("company_tax_regimes");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("valid_from", "date");
        column("simples_service_annex", "varchar(5)");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("business_activity", "varchar(30)");
        column("tax_regime", "varchar(30) not null");
        column("company_id", "varchar(36) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");

        index("idx_company_tax_regime_company", "company_id", "valid_from");
        foreignKey("fk_company_tax_regimes_company", "company_id", "companies");
    }
}
