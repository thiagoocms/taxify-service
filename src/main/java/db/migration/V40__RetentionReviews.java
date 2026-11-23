package db.migration;

/**
 * Conferência das retenções (M1): a retenção é calculada das notas; aqui só fica quem a
 * conferiu e quando, pela chave natural (documento + tributo + natureza).
 */
public class V40__RetentionReviews extends TableMigration {

    public V40__RetentionReviews() {
        super("retention_reviews");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("natural_key", "varchar(120) not null");
        column("reviewed_by", "varchar(50) not null");
        column("reviewed_at", "datetime(6) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_retention_reviews_company_key", "company_id", "natural_key");
        foreignKey("fk_retention_reviews_company", "company_id", "companies");
    }
}
