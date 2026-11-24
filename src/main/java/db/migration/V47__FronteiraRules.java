package db.migration;

/**
 * Fronteira PE: a tabela de ICMS do cálculo padrão (alíquota interna, ST com MVA, percentual do Simples)
 * e as fórmulas próprias da contadora, globais ou de uma empresa, com vigência e conferência.
 */
public class V47__FronteiraRules extends TableMigration {

    public V47__FronteiraRules() {
        super("fronteira_rules");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36)");
        column("kind", "varchar(30) not null");
        column("descricao", "varchar(255)");
        column("ncm", "varchar(10)");
        column("cest", "varchar(10)");
        column("codigo_produto", "varchar(60)");
        column("cnpj_fornecedor", "varchar(14)");
        column("aliquota", "decimal(9,4)");
        column("mva", "decimal(9,4)");
        column("percentual", "decimal(9,4)");
        column("formula", "varchar(500)");
        column("fundamento", "varchar(500)");
        column("link", "varchar(500)");
        column("valid_from", "date");
        column("valid_until", "date");
        column("enabled", "bit not null");
        column("reviewed_by", "varchar(50)");
        column("reviewed_at", "datetime(6)");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        index("idx_fronteira_rules_kind", "kind");
        index("idx_fronteira_rules_company", "company_id");
        foreignKey("fk_fronteira_rules_company", "company_id", "companies");
    }
}
