package db.migration;

/** Controle da sincronização de notas por empresa. */
public class V19__FiscalXmlSyncControl extends TableMigration {

    public V19__FiscalXmlSyncControl() {
        super("fiscal_xml_sync_control");

        column("id", "varchar(36) not null");
        column("data_emissao_fim", "date");
        column("data_emissao_inicio", "date");
        column("deleted", "bit not null");
        column("issued_events_checked_on", "date");
        column("issued_total_imported", "integer");
        column("issued_total_sieg", "integer");
        column("received_events_checked_on", "date");
        column("total_importado", "integer not null");
        column("total_sieg", "integer not null");
        column("created_date", "datetime(6) not null");
        column("issued_checked_at", "datetime(6)");
        column("last_modified_date", "datetime(6)");
        column("ultima_verificacao_em", "datetime(6) not null");
        column("company_id", "varchar(36) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");

        unique("uk_fiscal_xml_sync_control_company", "company_id");
        foreignKey("fk_fiscal_xml_sync_control_company", "company_id", "companies");
    }
}
