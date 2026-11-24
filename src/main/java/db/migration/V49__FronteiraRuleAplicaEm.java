package db.migration;

/**
 * Fronteira PE: a fórmula própria diz qual cálculo padrão ela substitui (sem encerramento, ST, Simples,
 * uso e consumo); vazio = qualquer um.
 */
public class V49__FronteiraRuleAplicaEm extends TableMigration {

    public V49__FronteiraRuleAplicaEm() {
        super("fronteira_rules");

        column("aplica_em", "varchar(30)");
    }
}
