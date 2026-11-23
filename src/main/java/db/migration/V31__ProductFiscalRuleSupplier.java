package db.migration;

/**
 * Regra fiscal por fornecedor: nas notas recebidas o código do produto é o do fornecedor, e
 * dois fornecedores podem usar o mesmo código para produtos diferentes. documento_fornecedor
 * (CNPJ/CPF, só dígitos) é opcional - vazio é a regra geral do código, como as existentes.
 * A chave única passa a ser empresa + código + fornecedor.
 */
public class V31__ProductFiscalRuleSupplier extends TableMigration {

    private static final String OLD_UNIQUE = "uk_product_fiscal_rules_company_product";
    private static final String NEW_UNIQUE = "uk_product_fiscal_rules_company_product_supplier";

    public V31__ProductFiscalRuleSupplier() {
        super("product_fiscal_rules");

        column("documento_fornecedor", "varchar(14)");

        unique(NEW_UNIQUE, "company_id", "codigo_produto", "documento_fornecedor");
    }

    /**
     * The new key goes in before the old one is dropped: company_id leads both, and MySQL needs
     * some index on it for the companies foreign key at all times.
     */
    @Override
    protected void beforeKeys() {
        if (!indexesOf(true).containsKey(NEW_UNIQUE)) {
            jdbc.execute("ALTER TABLE " + table() + " ADD CONSTRAINT " + NEW_UNIQUE
                    + " UNIQUE (company_id, codigo_produto, documento_fornecedor)");
            log.info("Created unique key {}", NEW_UNIQUE);
        }
        if (indexesOf(true).containsKey(OLD_UNIQUE)) {
            jdbc.execute("ALTER TABLE " + table() + " DROP INDEX " + OLD_UNIQUE);
            log.info("Dropped unique key {}", OLD_UNIQUE);
        }
    }
}
