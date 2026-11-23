package db.migration;

/** Captura de XML pela SEFAZ (NFeDistribuicaoDFe): último NSU lido e situação da última execução, por empresa. */
public class V29__SefazDistControl extends TableMigration {

    public V29__SefazDistControl() {
        super("sefaz_dist_control");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("ult_nsu", "varchar(15) not null");
        column("max_nsu", "varchar(15)");
        column("next_query_at", "datetime(6)");
        column("last_run_at", "datetime(6)");
        column("last_status", "varchar(20)");
        column("last_detail", "varchar(500)");
        column("last_new_notes", "integer");
        column("last_summaries", "integer");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_sefaz_dist_control_company", "company_id");
        foreignKey("fk_sefaz_dist_control_company", "company_id", "companies");
    }
}
