package db.migration;

/**
 * Notas que a SEFAZ entregou só como resumo (resNFe): a empresa é destinatária e ainda não
 * houve Ciência da Operação. Guardam o que o resumo traz, a situação da ciência enviada pelo
 * Taxify e, quando o XML completo chega (SEFAZ, SIEG ou upload), a data em que foi completada.
 */
public class V34__SefazNoteSummaries extends TableMigration {

    public V34__SefazNoteSummaries() {
        super("sefaz_note_summaries");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("access_key", "varchar(44) not null");
        column("nsu", "varchar(15)");
        column("issuer_document", "varchar(14)");
        column("issuer_name", "varchar(150)");
        column("issuer_ie", "varchar(20)");
        column("issue_date", "datetime(6)");
        column("operation_type", "varchar(1)");
        column("total_value", "decimal(15,2)");
        column("situation", "varchar(1)");
        column("authorized_at", "datetime(6)");
        column("protocol", "varchar(20)");
        column("ciencia_status", "varchar(20) not null");
        column("ciencia_at", "datetime(6)");
        column("ciencia_protocol", "varchar(20)");
        column("ciencia_detail", "varchar(255)");
        column("ciencia_attempts", "integer not null");
        column("last_fetch_at", "datetime(6)");
        column("fetch_detail", "varchar(255)");
        column("completed_at", "datetime(6)");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_sefaz_note_summaries_company_key", "company_id", "access_key");
        foreignKey("fk_sefaz_note_summaries_company", "company_id", "companies");
    }
}
