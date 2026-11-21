package db.migration;

/** Integrações do escritório (SIEG, Acessórias, Conta Azul). */
public class V13__FirmIntegrations extends TableMigration {

    public V13__FirmIntegrations() {
        super("firm_integrations");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("sync_processed", "integer");
        column("sync_total", "integer");
        column("connected_at", "datetime(6)");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("last_sync_at", "datetime(6)");
        column("oauth_state_created_at", "datetime(6)");
        column("sync_finished_at", "datetime(6)");
        column("sync_heartbeat_at", "datetime(6)");
        column("sync_started_at", "datetime(6)");
        column("token_expires_at", "datetime(6)");
        column("sync_status", "varchar(20)");
        column("type", "varchar(30) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("oauth_state", "varchar(64)");
        column("sync_error", "varchar(1000)");
        column("access_token", "TEXT");
        column("api_key", "TEXT");
        column("fee_category_ids", "TEXT");
        column("refresh_token", "TEXT");
        column("sync_result", "TEXT");

        unique("uk_firm_integration_type", "accounting_firm_id", "type");
        foreignKey("fk_firm_integrations_accounting_firm", "accounting_firm_id", "accounting_firms");
    }
}
