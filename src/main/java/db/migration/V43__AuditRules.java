package db.migration;

/**
 * Regras da Auditoria tipo malha (M9): fundamento legal, link, vigência e se o time fiscal já
 * conferiu a regra. Regra não conferida gera achado "a conferir", nunca afirmativo. As linhas
 * são criadas pela aplicação a partir do catálogo do código (AuditRuleDefinition).
 */
public class V43__AuditRules extends TableMigration {

    public V43__AuditRules() {
        super("audit_rules");

        column("id", "varchar(36) not null");
        column("code", "varchar(40) not null");
        column("title", "varchar(200) not null");
        column("rule_group", "varchar(20) not null");
        column("severity", "varchar(10) not null");
        column("legal_basis", "varchar(500)");
        column("link", "varchar(300)");
        column("valid_from", "date");
        column("valid_until", "date");
        column("enabled", "bit not null");
        column("reviewed_by", "varchar(50)");
        column("reviewed_at", "datetime(6)");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_audit_rules_code", "code");
    }
}
