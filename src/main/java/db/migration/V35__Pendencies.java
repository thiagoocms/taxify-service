package db.migration;

/**
 * Pendências (roadmap M13): uma tabela única onde todo módulo registra o que falta em cada
 * empresa. natural_key identifica a causa (ex.: o certificado, a nota) - uma linha por causa e
 * empresa; o gerador atualiza, fecha sozinho quando a causa some e reabre se ela voltar.
 */
public class V35__Pendencies extends TableMigration {

    public V35__Pendencies() {
        super("pendencies");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("type", "varchar(40) not null");
        column("severity", "varchar(10) not null");
        column("natural_key", "varchar(150) not null");
        column("competence", "varchar(7)");
        column("object_type", "varchar(20)");
        column("object_id", "varchar(64)");
        column("title", "varchar(200) not null");
        column("detail", "varchar(1000)");
        column("status", "varchar(15) not null");
        column("first_seen_at", "datetime(6) not null");
        column("last_seen_at", "datetime(6) not null");
        column("absent_since", "datetime(6)");
        column("closed_at", "datetime(6)");
        column("closed_by", "varchar(50)");
        column("close_comment", "varchar(500)");
        column("auto_closed", "bit not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_pendencies_company_key", "company_id", "natural_key");
        index("idx_pendencies_status_severity", "status", "severity");
        foreignKey("fk_pendencies_company", "company_id", "companies");
    }
}
