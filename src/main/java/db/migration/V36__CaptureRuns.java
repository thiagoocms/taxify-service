package db.migration;

/** Histórico das execuções de coleta (por enquanto a captura SEFAZ): uma linha por empresa e rodada. */
public class V36__CaptureRuns extends TableMigration {

    public V36__CaptureRuns() {
        super("capture_runs");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("service", "varchar(20) not null");
        column("started_at", "datetime(6) not null");
        column("finished_at", "datetime(6)");
        column("status", "varchar(20) not null");
        column("new_documents", "integer");
        column("detail", "varchar(500)");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        index("idx_capture_runs_company_started", "company_id", "started_at");
        foreignKey("fk_capture_runs_company", "company_id", "companies");
    }
}
