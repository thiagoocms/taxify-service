package db.migration;

/**
 * Notas fiscais de serviço (NFS-e), nos layouts nacional e ABRASF: prestadas e tomadas pela
 * empresa, com ISS e retenções federais (base do M1 Retenções). O XML fica no storage.
 */
public class V37__ServiceInvoices extends TableMigration {

    public V37__ServiceInvoices() {
        super("service_invoices");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("access_key", "varchar(80) not null");
        column("layout", "varchar(10) not null");
        column("numero", "varchar(20)");
        column("codigo_verificacao", "varchar(60)");
        column("issue_date", "datetime(6)");
        column("competence", "date");
        column("direction", "varchar(10) not null");
        column("prestador_documento", "varchar(14)");
        column("prestador_nome", "varchar(150)");
        column("prestador_municipio", "varchar(7)");
        column("tomador_documento", "varchar(14)");
        column("tomador_nome", "varchar(150)");
        column("municipio_incidencia", "varchar(7)");
        column("codigo_servico", "varchar(20)");
        column("codigo_tributacao_municipal", "varchar(30)");
        column("descricao_servico", "varchar(1000)");
        column("valor_servico", "decimal(15,2)");
        column("valor_deducoes", "decimal(15,2)");
        column("base_calculo", "decimal(15,2)");
        column("aliquota_iss", "decimal(9,4)");
        column("valor_iss", "decimal(15,2)");
        column("iss_retido", "bit not null");
        column("valor_iss_retido", "decimal(15,2)");
        column("valor_irrf", "decimal(15,2)");
        column("valor_pis", "decimal(15,2)");
        column("valor_cofins", "decimal(15,2)");
        column("pis_cofins_retidos", "bit not null");
        column("valor_csll", "decimal(15,2)");
        column("valor_inss", "decimal(15,2)");
        column("valor_liquido", "decimal(15,2)");
        column("canceled", "bit not null");
        column("xml_file_path", "varchar(1000) not null");
        column("source_system", "varchar(20) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_service_invoices_company_key", "company_id", "access_key");
        index("idx_service_invoices_company_issue", "company_id", "issue_date");
        foreignKey("fk_service_invoices_company", "company_id", "companies");
    }
}
