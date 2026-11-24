package db.migration;

/**
 * Fronteira PE: a memória de cálculo que o e-Fisco mostra para cada nota (PRConsultarNotaFiscalMemoria), buscada pelo
 * registro do extrato com o certificado do contador - para o contador comparar com o cálculo do Taxify.
 */
public class V53__EfiscoMemories extends TableMigration {

    public V53__EfiscoMemories() {
        super("efisco_memories");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("registro", "varchar(30) not null");
        column("chave_acesso", "varchar(44)");
        column("texto", "longtext not null");
        column("fetched_at", "datetime(6) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_efisco_memories_company_registro", "company_id", "registro");
        foreignKey("fk_efisco_memories_company", "company_id", "companies");
    }
}
