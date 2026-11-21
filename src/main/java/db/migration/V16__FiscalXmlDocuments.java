package db.migration;

/** Notas fiscais importadas. */
public class V16__FiscalXmlDocuments extends TableMigration {

    public V16__FiscalXmlDocuments() {
        super("fiscal_xml_documents");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("dest_end_uf", "varchar(2)");
        column("emit_end_uf", "varchar(2)");
        column("own_issue", "bit");
        column("total_base_calculo_icms", "decimal(19,2)");
        column("total_base_calculo_icms_st", "decimal(19,2)");
        column("total_valor_aproximado_tributos", "decimal(19,2)");
        column("total_valor_cofins", "decimal(19,2)");
        column("total_valor_desconto", "decimal(19,2)");
        column("total_valor_fcp", "decimal(19,2)");
        column("total_valor_fcp_st", "decimal(19,2)");
        column("total_valor_frete", "decimal(19,2)");
        column("total_valor_icms", "decimal(19,2)");
        column("total_valor_icms_desonerado", "decimal(19,2)");
        column("total_valor_icms_st", "decimal(19,2)");
        column("total_valor_importacao", "decimal(19,2)");
        column("total_valor_ipi", "decimal(19,2)");
        column("total_valor_outras_despesas", "decimal(19,2)");
        column("total_valor_pis", "decimal(19,2)");
        column("total_valor_produtos", "decimal(19,2)");
        column("total_valor_seguro", "decimal(19,2)");
        column("total_valor_total_nota", "decimal(19,2)");
        column("dest_indicador_ie", "varchar(5)");
        column("emit_regime_tributario", "varchar(5)");
        column("ide_ambiente", "varchar(5)");
        column("ide_codigo_uf", "varchar(5)");
        column("ide_consumidor_final", "varchar(5)");
        column("ide_destino_operacao", "varchar(5)");
        column("ide_finalidade_emissao", "varchar(5)");
        column("ide_indicador_presenca", "varchar(5)");
        column("ide_modelo", "varchar(5)");
        column("ide_serie", "varchar(5)");
        column("ide_tipo_operacao", "varchar(5)");
        column("canceled_at", "datetime(6)");
        column("created_date", "datetime(6) not null");
        column("ide_data_saida_entrada", "datetime(6)");
        column("issue_date", "datetime(6)");
        column("last_modified_date", "datetime(6)");
        column("last_rectification_checked_at", "datetime(6)");
        column("reviewed_at", "datetime(6)");
        column("dest_end_cep", "varchar(10)");
        column("dest_end_codigo_municipio", "varchar(10)");
        column("direction", "varchar(10)");
        column("document_type", "varchar(10) not null");
        column("emit_cnae", "varchar(10)");
        column("emit_end_cep", "varchar(10)");
        column("emit_end_codigo_municipio", "varchar(10)");
        column("ide_codigo_municipio_fato_gerador", "varchar(10)");
        column("dest_cpf", "varchar(11)");
        column("emit_cpf", "varchar(11)");
        column("review_status", "varchar(12)");
        column("status", "varchar(12)");
        column("issuer_cnpj", "varchar(14)");
        column("recipient_cnpj", "varchar(14)");
        column("dest_end_numero", "varchar(20)");
        column("dest_end_telefone", "varchar(20)");
        column("dest_id_estrangeiro", "varchar(20)");
        column("dest_inscricao_estadual", "varchar(20)");
        column("emit_end_numero", "varchar(20)");
        column("emit_end_telefone", "varchar(20)");
        column("emit_inscricao_estadual", "varchar(20)");
        column("emit_inscricao_estadual_st", "varchar(20)");
        column("emit_inscricao_municipal", "varchar(20)");
        column("ide_numero", "varchar(20)");
        column("source_system", "varchar(20) not null");
        column("company_id", "varchar(36) not null");
        column("access_key", "varchar(44)");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("reviewed_by", "varchar(50)");
        column("ide_natureza_operacao", "varchar(60)");
        column("dest_end_bairro", "varchar(100)");
        column("dest_end_complemento", "varchar(100)");
        column("dest_end_municipio", "varchar(100)");
        column("emit_end_bairro", "varchar(100)");
        column("emit_end_complemento", "varchar(100)");
        column("emit_end_municipio", "varchar(100)");
        column("dest_email", "varchar(200)");
        column("dest_end_logradouro", "varchar(200)");
        column("dest_nome", "varchar(200)");
        column("emit_end_logradouro", "varchar(200)");
        column("emit_nome", "varchar(200)");
        column("emit_nome_fantasia", "varchar(200)");
        column("review_note", "varchar(500)");
        column("rectified_file_path", "varchar(1000)");
        column("xml_file_path", "varchar(1000) not null");

        index("idx_fxd_company_issue", "company_id", "issue_date");
        index("idx_fxd_company_direction_issue", "company_id", "direction", "issue_date");
        unique("uk_fiscal_xml_document_company_key", "company_id", "access_key");
        foreignKey("fk_fiscal_xml_documents_company", "company_id", "companies");
    }

    /**
     * Notes used to be unique by access key alone - a note between two clients of the same
     * firm was kept for only one of them. That index goes; the key is unique per company.
     */
    @Override
    protected void beforeKeys() {
        indexesOf(true).forEach((name, indexColumns) -> {
            if (indexColumns.equals(java.util.List.of("access_key"))) {
                jdbc.execute("ALTER TABLE fiscal_xml_documents DROP INDEX `" + name + "`");
                log.info("Dropped the access-key-only unique index {}", name);
            }
        });
    }
}
