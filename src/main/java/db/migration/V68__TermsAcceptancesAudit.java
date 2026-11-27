package db.migration;

/**
 * Taxify SaaS fase 1: o aceite dos termos ganha as colunas de auditoria e o soft delete do padrão do projeto
 * (AbstractAuditingEntity). {@code accepted_at} continua a hora do aceite no relógio de Recife (F1-RN-06, F1-RN-11).
 */
public class V68__TermsAcceptancesAudit extends TableMigration {

    public V68__TermsAcceptancesAudit() {
        super("terms_acceptances");

        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");
    }

    /**
     * Linhas gravadas antes desta versão: quem gravou fica "system" e a data, a de accepted_at. Fuso: accepted_at é a hora de
     * America/Recife (ClockConfig), enquanto o created_date de auditoria das linhas novas é a hora da JVM (UTC no
     * container) - nas linhas antigas o created_date fica, portanto, 3 h "atrás" do que seria em UTC. Só informativo:
     * nenhuma regra lê o created_date desta tabela (accepted_at continua sendo a hora que conta).
     */
    @Override
    protected void beforeKeys() {
        jdbc.update("UPDATE terms_acceptances SET created_by = 'system' WHERE created_by IS NULL");
        jdbc.update("UPDATE terms_acceptances SET created_date = accepted_at WHERE created_date IS NULL");
    }
}
