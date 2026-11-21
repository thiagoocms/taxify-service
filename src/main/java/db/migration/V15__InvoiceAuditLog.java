package db.migration;

/** Log de auditoria das notas. */
public class V15__InvoiceAuditLog extends TableMigration {

    public V15__InvoiceAuditLog() {
        super("invoice_audit_log");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_date", "datetime(6)");
        column("status", "varchar(30) not null");
        column("company_id", "varchar(36) not null");
        column("user_id", "varchar(36)");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("external_invoice_key", "varchar(100) not null");
        column("notes", "varchar(500) not null");

        // stored as @Enumerated(ORDINAL) before - converted to the constant names
        ordinalEnum("status", "OK", "NEEDS_RECTIFICATION", "PENDING_DOCUMENTATION", "REJECTED", "RECTIFIED");

        foreignKey("fk_invoice_audit_log_company", "company_id", "companies");
        foreignKey("fk_invoice_audit_log_user", "user_id", "users");
    }
}
