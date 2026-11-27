package db.fiscaldocuments;

import db.migration.TableMigration;

import static db.fiscaldocuments.FiscalDocumentTables.DOCUMENTS;
import static db.fiscaldocuments.FiscalDocumentTables.EVENTS;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, 3.7): the events of every document type (cancelation, CC-e,
 * manifestation), empty - V87 fills it. {@code document_id} stays null until the note arrives; the unique key is the
 * idempotency of V18, and the access key takes 80 characters (CT-e with 44, national NFS-e with 50).
 */
public class V86_2__FiscalDocumentEvents extends TableMigration {

    public V86_2__FiscalDocumentEvents() {
        super(EVENTS);

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("document_id", "varchar(36)");
        column("access_key", "varchar(80) not null");
        column("event_type", "varchar(6) not null");
        column("sequence_number", "int not null");
        column("description", "varchar(120)");
        column("event_at", "datetime(6)");
        column("protocol", "varchar(30)");
        column("event_text", "TEXT");
        column("registered", "bit not null");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");
        column("deleted", "bit not null");

        unique("uk_fiscal_document_events", "company_id", "access_key", "event_type", "sequence_number");
        index("idx_fde_document", "document_id");
        foreignKey("fk_fiscal_document_events_company", "company_id", "companies");
        foreignKey("fk_fiscal_document_events_document", "document_id", DOCUMENTS);
    }
}
