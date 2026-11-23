package db.migration;

/**
 * Captura de NFS-e pelo Ambiente Nacional (ADN): último NSU lido e situação da última execução,
 * por empresa - o mesmo papel do sefaz_dist_control para a NF-e.
 */
public class V41__NfseAdnControl extends TableMigration {

    public V41__NfseAdnControl() {
        super("nfse_adn_control");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("ult_nsu", "bigint not null");
        column("next_query_at", "datetime(6)");
        column("last_run_at", "datetime(6)");
        column("last_status", "varchar(20)");
        column("last_detail", "varchar(500)");
        column("last_new_notes", "integer");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_nfse_adn_control_company", "company_id");
        foreignKey("fk_nfse_adn_control_company", "company_id", "companies");
    }
}
