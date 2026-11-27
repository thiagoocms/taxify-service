package db.migration;

/**
 * "Sincronizar agora" do painel da captura (spec 2026-11-27, CP-RN-15 a CP-RN-20): o pedido que põe a empresa no
 * início da fila SIEG. {@code open_key} = {@code company_id} enquanto o pedido está aberto (PENDING, RESERVED) e nulo
 * depois: a chave única dele deixa um só pedido aberto por empresa (dois cliques ao mesmo tempo). Os índices servem os
 * limites por escritório e por dia, a fila dos abertos e o cooldown por empresa. Limpeza dos de mais de 90 dias.
 */
public class V77__CapturePriorityRequests extends TableMigration {

    public V77__CapturePriorityRequests() {
        super("capture_priority_requests");

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("accounting_firm_id", "varchar(36) not null");
        column("requested_by", "varchar(36) not null");
        column("requested_scope", "varchar(10) not null");
        column("requested_at", "datetime(6) not null");
        column("status", "varchar(10) not null");
        column("served_at", "datetime(6)");
        column("open_key", "varchar(36)");
        column("deleted", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");

        unique("uk_capture_priority_requests_open_key", "open_key");
        index("idx_capture_priority_requests_firm_requested", "accounting_firm_id", "requested_at");
        index("idx_capture_priority_requests_status_requested", "status", "requested_at");
        index("idx_capture_priority_requests_company_served", "company_id", "served_at");
        foreignKey("fk_capture_priority_requests_company", "company_id", "companies");
        foreignKey("fk_capture_priority_requests_firm", "accounting_firm_id", "accounting_firms");
    }
}
