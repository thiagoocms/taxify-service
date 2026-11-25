package db.migration;

/**
 * Certidão SEFAZ-PE (roadmap M5): cada certidão estadual de uma empresa - CRF ou CND - com o que se lê do PDF
 * (número, emissão, validade, situação) e onde o PDF ficou guardado (no storage dos XMLs, como o extrato da
 * Fronteira). A mais recente válida é a que vale; as anteriores ficam de histórico.
 */
public class V56__CertidoesEstaduais extends TableMigration {

    public V56__CertidoesEstaduais() {
        super("certidoes_estaduais");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("uf", "varchar(2) not null");
        column("tipo", "varchar(10) not null");
        column("numero", "varchar(60)");
        column("situacao", "varchar(30) not null");
        column("emitida_em", "date");
        column("valida_ate", "date");
        column("documento", "varchar(20)");
        column("pdf_path", "varchar(500)");
        column("file_name", "varchar(255)");
        column("origem", "varchar(20) not null");
        column("imported_at", "datetime(6) not null");
        column("imported_by", "varchar(50) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        index("idx_certidoes_estaduais_company_validade", "company_id", "valida_ate");
        foreignKey("fk_certidoes_estaduais_company", "company_id", "companies");
    }
}
