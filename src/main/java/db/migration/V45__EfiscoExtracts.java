package db.migration;

/** Fronteira PE: o extrato do ICMS antecipado do e-Fisco importado por empresa e período (um por período). */
public class V45__EfiscoExtracts extends TableMigration {

    public V45__EfiscoExtracts() {
        super("efisco_extracts");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("periodo", "varchar(7) not null");
        column("numero_extrato", "varchar(30)");
        column("inscricao_estadual", "varchar(30)");
        column("razao_social", "varchar(255)");
        column("are", "varchar(120)");
        column("codigo_receita", "varchar(20)");
        column("credenciamento_antecipacao", "varchar(20)");
        column("credenciamento_interno", "varchar(255)");
        column("data_vencimento", "date");
        column("emitido_em", "datetime(6)");
        column("icms_a_recolher", "decimal(19,2)");
        column("icms_pago", "decimal(19,2)");
        column("icms_em_processo", "decimal(19,2)");
        column("icms_autuado", "decimal(19,2)");
        column("icms_mandado_seguranca", "decimal(19,2)");
        column("icms_anistiado", "decimal(19,2)");
        column("icms_reg_debito", "decimal(19,2)");
        column("file_name", "varchar(255)");
        column("imported_at", "datetime(6) not null");
        column("imported_by", "varchar(50) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_efisco_extracts_company_periodo", "company_id", "periodo");
        foreignKey("fk_efisco_extracts_company", "company_id", "companies");
    }
}
