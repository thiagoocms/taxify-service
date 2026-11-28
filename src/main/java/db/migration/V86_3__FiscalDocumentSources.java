package db.migration;

import db.migration.TableMigration;

import static db.migration.FiscalDocumentTables.DOCUMENTS;
import static db.migration.FiscalDocumentTables.SOURCES;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, 3.10, T4, FD-RN-31, 40): in which source each document was seen,
 * one row per document and source, in place of the {@code found_in_*_at} columns. {@code source} is an open value
 * ({@code SIEG}, {@code SEFAZ_DFE}, {@code NFSE_ADN}, {@code UPLOAD} and future providers), never a provider column.
 * {@code retrieval_mode} says how the source delivered it ({@code PERIOD}, {@code NSU}, {@code CURSOR}, {@code NONE}):
 * the SIEG skip of the period mode counts only {@code SIEG} + {@code PERIOD} (captura SIEG por NSU). One row per
 * document, source and mode. {@code company_id} is redundant on purpose (indexes per company and source). Empty - V87
 * fills it.
 */
public class V86_3__FiscalDocumentSources extends TableMigration {

    public V86_3__FiscalDocumentSources() {
        super(SOURCES);
        lockWaitSeconds(CutoverDatabase.LOCK_WAIT_SECONDS);

        column("id", "varchar(36) not null");
        column("document_id", "varchar(36) not null");
        column("company_id", "varchar(36) not null");
        column("source", "varchar(30) not null");
        column("retrieval_mode", "varchar(10) not null");
        column("first_seen_at", "datetime(6) not null");
        column("last_seen_at", "datetime(6) not null");

        unique("uk_fiscal_document_sources", "document_id", "source", "retrieval_mode");
        index("idx_fds_company_source_seen", "company_id", "source", "first_seen_at");
        foreignKey("fk_fiscal_document_sources_document", "document_id", DOCUMENTS);
    }
}
