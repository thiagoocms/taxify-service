package db.migration;

import java.util.ArrayList;
import java.util.List;

/**
 * The five tables of the fiscal documents remodel (spec 2026-11-27, 3.4 to 3.11), one migration each like the rest
 * of the schema ({@code TableMigration}): {@code V86} {@code fiscal_documents}, {@code V86_1} items, {@code V86_2}
 * events, {@code V86_3} sources and {@code V86_4} capture counts.
 *
 * <p>{@code TableMigration} only warns when a key can't be created. These tables are the base of the copy, so V87
 * starts by {@link #verify checking} that every declared index, foreign key and the {@code CHECK} is there, and stops
 * before touching the old tables otherwise.
 */
final class FiscalDocumentTables {

    static final String DOCUMENTS = "fiscal_documents";
    static final String ITEMS = "fiscal_document_items";
    static final String EVENTS = "fiscal_document_events";
    static final String SOURCES = "fiscal_document_sources";
    static final String COUNTS = "capture_period_counts";
    static final List<String> TABLES = List.of(DOCUMENTS, ITEMS, EVENTS, SOURCES, COUNTS);
    static final String DOCUMENT_TYPE_CHECK = "chk_fiscal_documents_document_type";

    private record Expected(String table, List<String> indexes, List<String[]> foreignKeys) {
    }

    private static final List<Expected> EXPECTED = List.of(
            new Expected(DOCUMENTS, List.of("uk_fiscal_documents_company_key", "idx_fd_company_type_issue",
                    "idx_fd_company_type_direction_issue", "idx_fd_company_type_source_created", "idx_fd_purge",
                    "idx_fd_type_metadata_version"), List.<String[]>of(new String[]{"company_id", "companies"})),
            new Expected(ITEMS, List.of("idx_fdi_document_cfop_amount"), List.<String[]>of(new String[]{"document_id", DOCUMENTS})),
            new Expected(EVENTS, List.of("uk_fiscal_document_events", "idx_fde_document"),
                    List.of(new String[]{"company_id", "companies"}, new String[]{"document_id", DOCUMENTS})),
            new Expected(SOURCES, List.of("uk_fiscal_document_sources", "idx_fds_company_source_seen"),
                    List.<String[]>of(new String[]{"document_id", DOCUMENTS})),
            new Expected(COUNTS, List.of("uk_capture_period_counts", "idx_cpc_channel_period"),
                    List.<String[]>of(new String[]{"company_id", "companies"})));

    private FiscalDocumentTables() {
    }

    static void verify(CutoverDatabase db) {
        List<String> missing = new ArrayList<>();
        for (Expected expected : EXPECTED) {
            if (!db.tableExists(expected.table())) {
                missing.add(expected.table());
                continue;
            }
            for (String index : expected.indexes()) {
                if (db.count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " AND table_name = ? AND index_name = ?", expected.table(), index) == 0) {
                    missing.add(expected.table() + "." + index);
                }
            }
            for (String[] fk : expected.foreignKeys()) {
                if (db.count("SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema = DATABASE()"
                        + " AND table_name = ? AND column_name = ? AND referenced_table_name = ?", expected.table(), fk[0], fk[1]) == 0) {
                    missing.add(expected.table() + "." + fk[0] + " -> " + fk[1]);
                }
            }
        }
        boolean keepsChecks = db.count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'information_schema'"
                + " AND table_name = 'CHECK_CONSTRAINTS'") > 0;
        if (keepsChecks && db.count("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE()"
                + " AND table_name = ? AND constraint_name = ?", DOCUMENTS, DOCUMENT_TYPE_CHECK) == 0) {
            missing.add(DOCUMENTS + "." + DOCUMENT_TYPE_CHECK);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("fiscal documents cutover stopped before the rename: V86 left these missing"
                    + " (see its warnings; fix and run again): " + missing);
        }
    }
}
