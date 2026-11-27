package db.migration;

/**
 * Taxify SaaS fase 1: os avisos de fim do teste ganham as colunas de auditoria e o soft delete do padrão do projeto
 * (AbstractAuditingEntity). {@code sent_at} continua a hora do envio no relógio de Recife (F1-RN-16).
 */
public class V69__FirmNotificationsAudit extends TableMigration {

    public V69__FirmNotificationsAudit() {
        super("firm_notifications");

        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");
    }

    /**
     * Linhas gravadas antes desta versão: quem gravou fica "system" e a data, a de sent_at. Fuso: sent_at é a hora de
     * America/Recife (ClockConfig), enquanto o created_date de auditoria das linhas novas é a hora da JVM (UTC no
     * container) - nas linhas antigas o created_date fica, portanto, 3 h "atrás" do que seria em UTC. Só informativo:
     * nenhuma regra lê o created_date desta tabela (sent_at continua sendo a hora que conta).
     */
    @Override
    protected void beforeKeys() {
        jdbc.update("UPDATE firm_notifications SET created_by = 'system' WHERE created_by IS NULL");
        jdbc.update("UPDATE firm_notifications SET created_date = sent_at WHERE created_date IS NULL");
    }
}
