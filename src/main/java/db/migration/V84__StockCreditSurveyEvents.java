package db.migration;

/**
 * Histórico da situação do contato no levantamento do crédito de estoque (spec 2026-11-27, F5a, CE-RN-10): de, para,
 * observação, quem (login) e quando (Recife). Poucos eventos por empresa. Tabela nova.
 */
public class V84__StockCreditSurveyEvents extends TableMigration {

    public V84__StockCreditSurveyEvents() {
        super("stock_credit_survey_events");

        column("id", "varchar(36) not null");
        column("survey_id", "varchar(36) not null");
        column("from_status", "varchar(25)");
        column("to_status", "varchar(25) not null");
        column("note", "varchar(500)");
        column("changed_by", "varchar(50) not null");
        column("changed_at", "datetime(6) not null");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        index("idx_scs_events_survey_at", "survey_id", "changed_at");
        foreignKey("fk_stock_credit_survey_events_survey", "survey_id", "stock_credit_surveys");
    }
}
