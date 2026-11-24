package db.migration;

/**
 * Fronteira PE: a mercadoria com agregação, como o e-Fisco - por NCM, o percentual de agregação da base
 * (CoefAgregacao: 20% = coeficiente 1,20), conforme a região de origem e se o fornecedor é do Simples.
 */
public class V50__FronteiraRuleAgregacao extends TableMigration {

    public V50__FronteiraRuleAgregacao() {
        super("fronteira_rules");

        column("agregacao", "decimal(9,4)");
        column("regiao_origem", "varchar(20)");
        column("fornecedor_simples", "bit");
    }
}
