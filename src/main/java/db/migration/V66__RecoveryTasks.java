package db.migration;

/**
 * Recuperação dos meses bloqueados: uma linha por empresa (SIEG) ou escritório (e-Fisco), fonte e mês. A chave única
 * (escopo, fonte, início) faz cada mês existir uma vez só; o cursor (página da SIEG) faz a recuperação continuar de
 * onde parou depois de um restart. PENDING até concluir; DONE ou SKIPPED (empresa sem o serviço, sem certificado).
 */
public class V66__RecoveryTasks extends TableMigration {

    public V66__RecoveryTasks() {
        super("recovery_tasks");

        column("id", "varchar(36) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("scope_id", "varchar(36) not null");
        column("company_id", "varchar(36)");
        column("source", "varchar(20) not null");
        column("period_start", "date not null");
        column("period_end", "date not null");
        column("page_cursor", "int not null");
        column("status", "varchar(10) not null");
        column("attempts", "int not null");
        column("last_error", "varchar(200)");
        column("created_date", "datetime(6) not null");
        column("done_at", "datetime(6)");

        unique("uk_recovery_tasks", "scope_id", "source", "period_start");
        index("idx_recovery_tasks_firm_status", "accounting_firm_id", "status");
        index("idx_recovery_tasks_company_status", "company_id", "status");
        foreignKey("fk_recovery_tasks_firm", "accounting_firm_id", "accounting_firms");
    }
}
