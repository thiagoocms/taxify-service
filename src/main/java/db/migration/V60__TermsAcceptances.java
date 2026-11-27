package db.migration;

/**
 * Taxify SaaS fase 1 (F1-RN-11, F1-RN-06): o aceite dos termos no cadastro - versão, data e hora (America/Recife) e IP.
 * Também é a contagem do limite de cadastros por IP por dia, por isso o índice (ip, accepted_at).
 */
public class V60__TermsAcceptances extends TableMigration {

    public V60__TermsAcceptances() {
        super("terms_acceptances");

        column("id", "varchar(36) not null");
        column("user_id", "varchar(36) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("terms_version", "varchar(50) not null");
        column("accepted_at", "datetime(6) not null");
        column("ip", "varchar(45) not null");

        index("idx_terms_acceptances_ip", "ip", "accepted_at");
        index("idx_terms_acceptances_firm", "accounting_firm_id");
        foreignKey("fk_terms_acceptances_user", "user_id", "users");
        foreignKey("fk_terms_acceptances_firm", "accounting_firm_id", "accounting_firms");
    }
}
