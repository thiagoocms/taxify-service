package db.migration;

/** Regras fiscais por produto (NCM/CFOP corretos). */
public class V14__ProductFiscalRules extends TableMigration {

    public V14__ProductFiscalRules() {
        super("product_fiscal_rules");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("cfop_correto", "varchar(10) not null");
        column("ncm_correto", "varchar(10)");
        column("company_id", "varchar(36) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("codigo_produto", "varchar(60) not null");
        column("descricao_produto", "varchar(500)");
        column("observacoes", "varchar(1000)");

        unique("uk_product_fiscal_rules_company_product", "company_id", "codigo_produto");
        foreignKey("fk_product_fiscal_rules_company", "company_id", "companies");
    }
}
