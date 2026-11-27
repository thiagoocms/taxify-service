package db.fiscaldocuments;

import db.migration.TableMigration;

import static db.fiscaldocuments.FiscalDocumentTables.COUNTS;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, 3.11, T2, FD-RN-32, 40): the capture counts per company, channel,
 * document type, side and month, in place of the counter columns of {@code fiscal_xml_sync_control}. The unique key is
 * the idempotency of the upsert (two instances). Empty - V87 copies the counters into it.
 */
public class V86_4__CapturePeriodCounts extends TableMigration {

    public V86_4__CapturePeriodCounts() {
        super(COUNTS);

        column("id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("channel", "varchar(30) not null");
        column("document_type", "varchar(10) not null");
        column("side", "varchar(10) not null");
        column("period_month", "char(7) not null");
        column("window_start", "date");
        column("window_end", "date");
        column("provider_total", "int");
        column("provider_counted_at", "datetime(6)");
        column("imported_total", "int not null default 0");
        column("stored_any_source_total", "int");
        column("known_gap", "int");
        column("imported_counted_at", "datetime(6)");
        column("closed_at", "datetime(6)");
        column("created_by", "varchar(50) not null");
        column("created_date", "datetime(6) not null");
        column("last_modified_by", "varchar(50)");
        column("last_modified_date", "datetime(6)");
        column("deleted", "bit not null");

        unique("uk_capture_period_counts", "company_id", "channel", "document_type", "side", "period_month");
        index("idx_cpc_channel_period", "channel", "document_type", "period_month");
        foreignKey("fk_capture_period_counts_company", "company_id", "companies");
    }
}
