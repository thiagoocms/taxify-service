package db.migration;

/**
 * Painel da captura (spec 2026-11-27, CP-RN-10): o último resultado de cada etapa da captura por empresa - SIEG
 * (lados, eventos, NFS-e, recuperação), SEFAZ e ADN. Uma linha por (company_id, stage), gravada por upsert ao fim da
 * etapa. Só códigos da lista fechada (CP-RN-11) e status numéricos de até 4 dígitos: nada de texto do provedor.
 */
public class V76__CaptureStageStatus extends TableMigration {

    public V76__CaptureStageStatus() {
        super("capture_stage_status");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("stage", "varchar(30) not null");
        column("last_outcome", "varchar(10) not null");
        column("last_outcome_at", "datetime(6) not null");
        column("last_success_at", "datetime(6)");
        column("last_failure_at", "datetime(6)");
        column("failure_code", "varchar(40)");
        column("provider_status", "varchar(4)");
        column("warning_code", "varchar(40)");
        column("warning_count", "int");
        column("consecutive_failures", "int not null default 0");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_capture_stage_status_company_stage", "company_id", "stage");
        foreignKey("fk_capture_stage_status_company", "company_id", "companies");
    }
}
