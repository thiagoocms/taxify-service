package db.migration;

/** Fronteira PE: as notas de um extrato do e-Fisco, como vieram (a nota do Taxify é achada na leitura). */
public class V46__EfiscoExtractNotes extends TableMigration {

    public V46__EfiscoExtractNotes() {
        super("efisco_extract_notes");

        column("id", "varchar(36) not null");
        column("extract_id", "varchar(36) not null");
        column("linha", "integer not null");
        column("registro", "varchar(40)");
        column("data_passagem", "varchar(20)");
        column("numero_nota", "varchar(20) not null");
        column("uf", "varchar(2)");
        column("valor_nota", "decimal(19,2) not null");
        column("icms_devido", "decimal(19,2) not null");
        column("cnpj_emitente", "varchar(14) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        index("idx_efisco_extract_notes_extract", "extract_id");
        foreignKey("fk_efisco_extract_notes_extract", "extract_id", "efisco_extracts");
    }
}
