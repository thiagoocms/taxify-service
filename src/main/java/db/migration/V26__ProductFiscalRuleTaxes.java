package db.migration;

/**
 * Regras fiscais por produto: classificação tributária além de CFOP/NCM (origem, CST/CSOSN,
 * alíquota interna de ICMS, CEST, CST/alíquota de IPI, PIS e COFINS, CST e cClassTrib de
 * IBS/CBS). Todas opcionais - nulo é "não conferir", então as regras existentes seguem iguais.
 */
public class V26__ProductFiscalRuleTaxes extends TableMigration {

    public V26__ProductFiscalRuleTaxes() {
        super("product_fiscal_rules");

        column("origem_mercadoria", "varchar(1)");
        column("cst_icms", "varchar(2)");
        column("csosn", "varchar(3)");
        column("aliquota_icms", "decimal(7,4)");
        column("cest", "varchar(7)");
        column("cst_ipi", "varchar(2)");
        column("aliquota_ipi", "decimal(7,4)");
        column("cst_pis", "varchar(2)");
        column("aliquota_pis", "decimal(7,4)");
        column("cst_cofins", "varchar(2)");
        column("aliquota_cofins", "decimal(7,4)");
        column("cst_ibscbs", "varchar(3)");
        column("class_trib_ibscbs", "varchar(6)");
    }
}
