package db.migration;

/**
 * Recuperação dos meses bloqueados: os pedaços ganham o resto das colunas de auditoria e o soft delete do padrão do
 * projeto (AbstractAuditingEntity). {@code created_date} já existia e passa a ser a da auditoria. Fuso: nas linhas
 * antigas ele foi gravado com o relógio de America/Recife (ClockConfig); nas novas é a hora da JVM do listener de
 * auditoria (UTC no container). Só informativo: nenhuma regra da recuperação lê o created_date.
 */
public class V70__RecoveryTasksAudit extends TableMigration {

    public V70__RecoveryTasksAudit() {
        super("recovery_tasks");

        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");
    }

    /** Linhas gravadas antes desta versão: quem gravou fica "system". */
    @Override
    protected void beforeKeys() {
        jdbc.update("UPDATE recovery_tasks SET created_by = 'system' WHERE created_by IS NULL");
    }
}
