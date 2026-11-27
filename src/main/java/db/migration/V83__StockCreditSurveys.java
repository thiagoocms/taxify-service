package db.migration;

/**
 * Levantamento do crédito presumido de CBS sobre o estoque (spec 2026-11-27, F5a): uma linha por empresa e ano (2026).
 * As colunas de cálculo são gravadas pela rotina e pelo "Atualizar agora" com {@code INSERT ... ON DUPLICATE KEY
 * UPDATE} na chave única (CE-RN-08 b); a situação do contato, a observação e {@code version} só mudam pela tela
 * (CE-RN-10). {@code calculated_at} nulo = linha só reservada pelo cooldown (CE-RN-09). Tabela nova (até ~5.000 linhas).
 */
public class V83__StockCreditSurveys extends TableMigration {

    public V83__StockCreditSurveys() {
        super("stock_credit_surveys");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("survey_year", "int not null");
        column("reference_regime", "varchar(30)");
        column("reference_regime_since", "date");
        column("reference_regime_source", "varchar(12)");
        column("next_year_regime", "varchar(30)");
        column("classification", "varchar(20)");
        column("purchases_total", "decimal(19,2)");
        column("purchase_items", "int");
        column("inbound_notes", "int");
        column("months_with_inbound", "int");
        column("months_evaluated", "int");
        column("coverage", "varchar(15)");
        column("first_inbound", "date");
        column("last_inbound", "date");
        column("purchases_by_month_json", "text");
        column("purchases_by_cst_json", "text");
        column("top_ncm_json", "text");
        column("calculated_at", "datetime(6)");
        column("refresh_requested_at", "datetime(6)");
        column("contact_status", "varchar(25) not null default 'NOT_EVALUATED'");
        column("status_changed_by", "varchar(50)");
        column("status_changed_at", "datetime(6)");
        column("note", "varchar(500)");
        column("version", "int not null default 0");
        column("deleted", "bit not null default 0");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_stock_credit_surveys_company_year", "company_id", "survey_year");
        index("idx_scs_year_class", "survey_year", "classification");
        foreignKey("fk_stock_credit_surveys_company", "company_id", "companies");
    }
}
