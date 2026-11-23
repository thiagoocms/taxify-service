package db.migration;

/** Execuções da Auditoria tipo malha: quando cada empresa/competência foi auditada e quantos achados deu. */
public class V44__AuditRuns extends TableMigration {

    public V44__AuditRuns() {
        super("audit_runs");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("competence", "varchar(7) not null");
        column("ran_at", "datetime(6) not null");
        column("ran_by", "varchar(50) not null");
        column("findings", "integer not null");
        column("notes_checked", "integer not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_audit_runs_company_competence", "company_id", "competence");
        foreignKey("fk_audit_runs_company", "company_id", "companies");
    }
}
