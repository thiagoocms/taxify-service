package db.migration;

/**
 * Histórico das alterações das alíquotas de IBS/CBS por ano (spec 2026-11-27, F1a-0, AL-RN-04): um evento por gravação
 * que muda algum campo, com antes e depois, o login de quem mudou e a hora de Recife. Tabela nova e pequena.
 */
public class V81__IbsCbsRateChanges extends TableMigration {

    public V81__IbsCbsRateChanges() {
        super("ibs_cbs_rate_changes");

        column("id", "varchar(36) not null");
        column("rate_year", "int not null");
        column("action", "varchar(10) not null");
        column("status_before", "varchar(12)");
        column("status_after", "varchar(12)");
        column("cbs_before", "decimal(7,4)");
        column("cbs_after", "decimal(7,4)");
        column("ibs_state_before", "decimal(7,4)");
        column("ibs_state_after", "decimal(7,4)");
        column("ibs_municipal_before", "decimal(7,4)");
        column("ibs_municipal_after", "decimal(7,4)");
        column("legal_basis_after", "varchar(500)");
        column("source_url_after", "varchar(300)");
        column("changed_by", "varchar(50) not null");
        column("changed_at", "datetime(6) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        index("idx_ibs_cbs_rate_changes_year_at", "rate_year", "changed_at");
    }
}
