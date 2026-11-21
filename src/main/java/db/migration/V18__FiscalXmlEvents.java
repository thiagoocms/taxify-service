package db.migration;

/** Eventos SEFAZ das notas (cancelamento, CC-e, manifestação). */
public class V18__FiscalXmlEvents extends TableMigration {

    public V18__FiscalXmlEvents() {
        super("fiscal_xml_events");

        column("id", "varchar(36) not null");
        column("deleted", "bit not null");
        column("registered", "bit not null");
        column("sequence_number", "integer not null");
        column("event_type", "varchar(6) not null");
        column("created_date", "datetime(6) not null");
        column("event_at", "datetime(6)");
        column("last_modified_date", "datetime(6)");
        column("protocol", "varchar(30)");
        column("company_id", "varchar(36) not null");
        column("document_id", "varchar(36)");
        column("access_key", "varchar(44) not null");
        column("created_by", "varchar(50) not null");
        column("last_modified_by", "varchar(50)");
        column("description", "varchar(120)");
        column("event_text", "TEXT");

        index("idx_fiscal_xml_event_key", "company_id", "access_key");
        unique("uk_fiscal_xml_event", "company_id", "access_key", "event_type", "sequence_number");
        foreignKey("fk_fiscal_xml_events_company", "company_id", "companies");
        foreignKey("fk_fiscal_xml_events_document", "document_id", "fiscal_xml_documents");
    }
}
