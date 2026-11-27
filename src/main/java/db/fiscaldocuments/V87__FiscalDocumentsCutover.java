package db.fiscaldocuments;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, B-FD-03, FD-RN-20, 24, 26): the old tables become
 * {@code legacy_*} and their rows are copied into the new ones, with the same ids.
 * <ol>
 *   <li><b>Checks, before touching anything:</b> no id of {@code service_invoices} is also an id of
 *   {@code fiscal_xml_documents} (FD-RN-20, log {@code fiscal.documents.cutover.id.collision}), no access key is in
 *   both for the same company (the new unique key), no value the copy can't translate (document type, direction,
 *   status, NFS-e layout, model longer than 2) and every column the copy reads exists. Any failure stops here: no
 *   rename, nothing copied.</li>
 *   <li><b>Rename</b> of the four tables in one {@code RENAME TABLE} (atomic). Tables already renamed are skipped - a
 *   run that fell after the rename goes straight to the copy.</li>
 *   <li><b>Copy</b> in the fixed order of FD-RN-24 (documents that are not NFS-e, NFS-e, items, events), in batches of
 *   {@value #BATCH} ids (keyset over the primary key), each batch committed on its own and inserting only the ids the
 *   new table doesn't have yet. A run that fell in the middle (lock, disk, kill) is started again after
 *   {@code flyway repair} and copies only what is missing.</li>
 *   <li><b>Sources</b> (3.10): {@code found_in_sieg_at} → {@code SIEG}, {@code found_in_sefaz_at} →
 *   {@code SEFAZ_DFE}, {@code found_in_adn_at} → {@code NFSE_ADN}, {@code source_system = 'UPLOAD'} → {@code UPLOAD}
 *   with {@code created_date}; one row per document and source.</li>
 *   <li><b>Capture counts</b> (3.11): each {@code fiscal_xml_sync_control} row becomes its rows in
 *   {@code capture_period_counts} (channel {@code SIEG}, type {@code NFE}), the table itself stays (the queue uses
 *   it). Idempotent by the unique key.</li>
 * </ol>
 * The migration runs outside a transaction (MySQL commits DDL anyway): every statement is its own unit, which is what
 * makes it resumable. A new database without the old tables (CI, a fresh environment) has nothing to copy: steps 1
 * to 4 are skipped and step 5 finds no row.
 */
public class V87__FiscalDocumentsCutover extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V87__FiscalDocumentsCutover.class);
    static final int BATCH = 5_000;

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) {
        CutoverDatabase db = new CutoverDatabase(context);
        db.withShortLockWait("V87 cutover", () -> run(db));
    }

    private void run(CutoverDatabase db) {
        FiscalDocumentTables.verify(db);
        Map<LegacyTable, LegacyTable.Location> locations = new EnumMap<>(LegacyTable.class);
        for (LegacyTable table : LegacyTable.values()) {
            locations.put(table, table.locate(db));
        }
        boolean anyLegacy = locations.values().stream().anyMatch(location -> location != LegacyTable.Location.ABSENT);
        if (anyLegacy) {
            check(db, locations);
            rename(db, locations);
            copyDocuments(db, locations);
            copySources(db, locations);
        } else {
            log.info("fiscal.documents.cutover.nothing.to.do reason=no-legacy-tables");
        }
        copyCaptureCounts(db);
    }

    private static String nameOf(LegacyTable table, Map<LegacyTable, LegacyTable.Location> locations) {
        return locations.get(table) == LegacyTable.Location.ORIGINAL ? table.originalName() : table.legacyName();
    }

    private static boolean present(LegacyTable table, Map<LegacyTable, LegacyTable.Location> locations) {
        return locations.get(table) != LegacyTable.Location.ABSENT;
    }

    // ------------------------------------------------------------------ 1. checks

    private void check(CutoverDatabase db, Map<LegacyTable, LegacyTable.Location> locations) {
        List<String> problems = new ArrayList<>();
        String documents = nameOf(LegacyTable.DOCUMENTS, locations);
        String invoices = nameOf(LegacyTable.SERVICE_INVOICES, locations);
        boolean hasDocuments = present(LegacyTable.DOCUMENTS, locations);
        boolean hasInvoices = present(LegacyTable.SERVICE_INVOICES, locations);

        requireColumns(db, problems, LegacyTable.DOCUMENTS, locations, CopyMappings.goodsInvoice());
        requireColumns(db, problems, LegacyTable.SERVICE_INVOICES, locations, CopyMappings.serviceInvoice());
        requireColumns(db, problems, LegacyTable.ITEMS, locations, CopyMappings.item());
        requireColumns(db, problems, LegacyTable.EVENTS, locations, CopyMappings.event());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("fiscal documents cutover stopped before the rename, nothing copied: " + problems);
        }

        if (hasDocuments && hasInvoices) {
            long collisions = db.count("SELECT COUNT(*) FROM " + invoices + " s JOIN " + documents + " d ON d.id = s.id");
            if (collisions > 0) {
                log.error("fiscal.documents.cutover.id.collision count={}", collisions);
                problems.add(collisions + " id(s) of " + invoices + " also in " + documents + " (FD-RN-20)");
            }
            long sameKey = db.count("SELECT COUNT(*) FROM " + invoices + " s JOIN " + documents + " d"
                    + " ON d.company_id = s.company_id AND d.access_key = s.access_key");
            if (sameKey > 0) {
                problems.add(sameKey + " access key(s) both in " + invoices + " and " + documents + " for the same company");
            }
        }
        if (hasDocuments) {
            unexpected(db, problems, documents, "document_type NOT IN ('NFE', 'CTE')", "document_type other than NFE/CTE");
            unexpected(db, problems, documents, "direction IS NOT NULL AND direction NOT IN ('INBOUND', 'OUTBOUND')", "unknown direction");
            unexpected(db, problems, documents, "status IS NOT NULL AND status NOT IN ('AUTHORIZED', 'CANCELED')", "unknown status");
            unexpected(db, problems, documents, "CHAR_LENGTH(TRIM(ide_modelo)) > 2", "ide_modelo longer than 2");
        }
        if (hasInvoices) {
            unexpected(db, problems, invoices, "direction NOT IN ('PRESTADA', 'TOMADA', 'TERCEIRO')", "unknown NFS-e direction");
            unexpected(db, problems, invoices, "layout NOT IN ('NACIONAL', 'ABRASF')", "unknown NFS-e layout");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("fiscal documents cutover stopped before the rename, nothing copied: " + problems);
        }
    }

    private static void requireColumns(CutoverDatabase db, List<String> problems, LegacyTable table,
                                       Map<LegacyTable, LegacyTable.Location> locations, Map<String, String> mapping) {
        if (!present(table, locations)) {
            return;
        }
        String name = nameOf(table, locations);
        for (String column : CopyMappings.legacyColumns(mapping)) {
            if (!db.columnExists(name, column)) {
                problems.add("column " + name + "." + column + " missing");
            }
        }
    }

    private static void unexpected(CutoverDatabase db, List<String> problems, String table, String condition, String what) {
        long rows = db.count("SELECT COUNT(*) FROM " + table + " WHERE " + condition);
        if (rows > 0) {
            problems.add(rows + " row(s) of " + table + " with " + what);
        }
    }

    // ------------------------------------------------------------------ 2. rename

    private void rename(CutoverDatabase db, Map<LegacyTable, LegacyTable.Location> locations) {
        List<String> pairs = new ArrayList<>();
        for (LegacyTable table : LegacyTable.values()) {
            if (locations.get(table) == LegacyTable.Location.ORIGINAL) {
                pairs.add(table.originalName() + " TO " + table.legacyName());
            }
        }
        if (pairs.isEmpty()) {
            log.info("fiscal.documents.cutover.rename skipped=already-renamed");
            return;
        }
        long started = System.currentTimeMillis();
        db.jdbc.execute("RENAME TABLE " + String.join(", ", pairs));
        for (LegacyTable table : LegacyTable.values()) {
            if (locations.get(table) == LegacyTable.Location.ORIGINAL) {
                locations.put(table, LegacyTable.Location.LEGACY);
            }
        }
        log.info("fiscal.documents.cutover.renamed tables={} ms={}", pairs.size(), System.currentTimeMillis() - started);
    }

    // ------------------------------------------------------------------ 3. copy

    private void copyDocuments(CutoverDatabase db, Map<LegacyTable, LegacyTable.Location> locations) {
        if (present(LegacyTable.DOCUMENTS, locations)) {
            String source = LegacyTable.DOCUMENTS.legacyName();
            copy(db, "documents", source, FiscalDocumentTables.DOCUMENTS, CopyMappings.goodsInvoice(), "l.document_type <> 'CTE'");
            copy(db, "transport-documents", source, FiscalDocumentTables.DOCUMENTS, CopyMappings.cte(), "l.document_type = 'CTE'");
        }
        if (present(LegacyTable.SERVICE_INVOICES, locations)) {
            copy(db, "service-invoices", LegacyTable.SERVICE_INVOICES.legacyName(), FiscalDocumentTables.DOCUMENTS,
                    CopyMappings.serviceInvoice(), null);
        }
        if (present(LegacyTable.ITEMS, locations)) {
            copy(db, "items", LegacyTable.ITEMS.legacyName(), FiscalDocumentTables.ITEMS, CopyMappings.item(), null);
        }
        if (present(LegacyTable.EVENTS, locations)) {
            copy(db, "events", LegacyTable.EVENTS.legacyName(), FiscalDocumentTables.EVENTS, CopyMappings.event(), null);
        }
    }

    /**
     * Keyset batches over the legacy primary key: the upper id of the next {@value #BATCH} rows, then one
     * {@code INSERT ... SELECT} of that range with {@code NOT EXISTS} on the target id. Each statement commits alone.
     */
    private void copy(CutoverDatabase db, String step, String source, String target, Map<String, String> mapping, String filter) {
        long started = System.currentTimeMillis();
        String where = filter == null ? "" : " AND " + filter;
        String insert = "INSERT INTO " + target + " (" + String.join(", ", mapping.keySet()) + ") SELECT "
                + String.join(", ", mapping.values()) + " FROM " + source + " l WHERE l.id > ? AND l.id <= ?" + where
                + " AND NOT EXISTS (SELECT 1 FROM " + target + " n WHERE n.id = l.id)";
        String insertLast = insert.replace("l.id > ? AND l.id <= ?", "l.id > ?");
        long rows = db.count("SELECT COUNT(*) FROM " + source + " l WHERE 1 = 1" + where);
        long copied = 0;
        String from = "";
        while (true) {
            List<String> upper = db.strings("SELECT id FROM " + source + " WHERE id > ? ORDER BY id LIMIT 1 OFFSET " + (BATCH - 1), from);
            if (upper.isEmpty()) {
                copied += db.jdbc.update(insertLast, from);
                break;
            }
            copied += db.jdbc.update(insert, from, upper.getFirst());
            from = upper.getFirst();
        }
        log.info("fiscal.documents.cutover.copied step={} table={} copied={} existing={} ms={}",
                step, target, copied, rows - copied, System.currentTimeMillis() - started);
    }

    // ------------------------------------------------------------------ 4. sources

    private void copySources(CutoverDatabase db, Map<LegacyTable, LegacyTable.Location> locations) {
        long started = System.currentTimeMillis();
        long copied = 0;
        if (present(LegacyTable.DOCUMENTS, locations)) {
            String source = LegacyTable.DOCUMENTS.legacyName();
            copied += copySource(db, source, "found_in_sieg_at", "SIEG");
            copied += copySource(db, source, "found_in_sefaz_at", "SEFAZ_DFE");
            copied += copySource(db, source, null, "UPLOAD");
        }
        if (present(LegacyTable.SERVICE_INVOICES, locations)) {
            String source = LegacyTable.SERVICE_INVOICES.legacyName();
            copied += copySource(db, source, "found_in_sieg_at", "SIEG");
            copied += copySource(db, source, "found_in_adn_at", "NFSE_ADN");
            copied += copySource(db, source, null, "UPLOAD");
        }
        log.info("fiscal.documents.cutover.copied step=sources table={} copied={} ms={}",
                FiscalDocumentTables.SOURCES, copied, System.currentTimeMillis() - started);
    }

    /** {@code dateColumn} null = the upload origin, dated by the document's {@code created_date}. */
    private static long copySource(CutoverDatabase db, String table, String dateColumn, String sourceKey) {
        if (dateColumn != null && !db.columnExists(table, dateColumn)) {
            return 0;
        }
        String date = dateColumn == null ? "l.created_date" : "l." + dateColumn;
        String condition = dateColumn == null ? "l.source_system = 'UPLOAD'" : "l." + dateColumn + " IS NOT NULL";
        return db.jdbc.update("INSERT INTO " + FiscalDocumentTables.SOURCES
                + " (id, document_id, company_id, source, first_seen_at, last_seen_at)"
                + " SELECT UUID(), l.id, l.company_id, ?, " + date + ", " + date + " FROM " + table + " l WHERE " + condition
                + " AND NOT EXISTS (SELECT 1 FROM " + FiscalDocumentTables.SOURCES + " s WHERE s.document_id = l.id AND s.source = ?)",
                sourceKey, sourceKey);
    }

    // ------------------------------------------------------------------ 5. capture counts

    /**
     * Per control row and side (3.11): the current month (window start), the previous month of V79 (closed) and the
     * month of the V85 known gap when it is neither (closed, unless it is the current month). The first row written
     * for a (side, month) wins - a previous month equal to the current one (window moved back by a manual sync) keeps
     * the current counters.
     */
    private void copyCaptureCounts(CutoverDatabase db) {
        if (!SyncControlCounters.present(db)) {
            log.info("fiscal.documents.cutover.counts skipped=no-counter-columns");
            return;
        }
        long started = System.currentTimeMillis();
        String t = SyncControlCounters.TABLE;
        Timestamp now = Timestamp.valueOf(CutoverDatabase.nowInRecife());
        String month = "DATE_FORMAT(c.data_emissao_inicio, '%Y-%m')";
        long copied = 0;
        for (String side : List.of("RECEIVED", "ISSUED")) {
            boolean received = side.equals("RECEIVED");
            String providerTotal = received ? "c.total_sieg" : "c.issued_total_sieg";
            String imported = received ? "c.total_importado" : "c.issued_total_imported";
            String checkedAt = received ? "c.ultima_verificacao_em" : "c.issued_checked_at";
            String prefix = received ? "received" : "issued";
            String stored = db.columnOrNull(t, "c", prefix + "_stored_any_source");
            List<String[]> gapSlots = new ArrayList<>();
            for (String slot : SyncControlCounters.GAP_SLOTS) {
                gapSlots.add(new String[]{db.columnOrNull(t, "c", prefix + "_" + slot + "_month"),
                        db.columnOrNull(t, "c", prefix + "_" + slot)});
            }
            String prevMonth = "TRIM(c." + prefix + "_prev_month)";

            // the issued side only when it was ever checked; it uses the received side's window (one window per row)
            String current = "c.data_emissao_inicio IS NOT NULL" + (received ? ""
                    : " AND (c.issued_total_sieg IS NOT NULL OR c.issued_total_imported IS NOT NULL OR c.issued_checked_at IS NOT NULL)");
            copied += insertCounts(db, side, month, "c.data_emissao_inicio", "c.data_emissao_fim", providerTotal, checkedAt,
                    imported, stored, gapOf(gapSlots, month), checkedAt, "NULL", current, null);
            // a previous month equal to the current one is not copied (the current row wins)
            String previous = CopyMappings.hasText(prevMonth)
                    + " AND (c.data_emissao_inicio IS NULL OR " + prevMonth + " <> " + month + ")";
            copied += insertCounts(db, side, prevMonth, "NULL", "NULL", "c." + prefix + "_prev_total_sieg", "NULL",
                    "c." + prefix + "_prev_imported", "NULL", gapOf(gapSlots, prevMonth), "NULL", "?", previous, now);
            for (String[] slot : gapSlots) {
                copied += insertCounts(db, side, slot[0], "NULL", "NULL", "NULL", "NULL", "0", "NULL", slot[1], "NULL",
                        "CASE WHEN c.data_emissao_inicio IS NOT NULL AND " + slot[0] + " = " + month + " THEN NULL ELSE ? END",
                        slot[0] + " IS NOT NULL AND " + slot[1] + " IS NOT NULL", now);
            }
        }
        long controls = db.count("SELECT COUNT(*) FROM " + t);
        long withoutWindow = db.count("SELECT COUNT(*) FROM " + t + " WHERE data_emissao_inicio IS NULL");
        log.info("fiscal.documents.cutover.copied step=capture-counts table={} controls={} copied={} withoutWindow={} ms={}",
                FiscalDocumentTables.COUNTS, controls, copied, withoutWindow, System.currentTimeMillis() - started);
    }

    /** The known gap of a month: the first V85 slot ({@code known_gap}, then {@code earlier_gap}) holding that month. */
    private static String gapOf(List<String[]> slots, String month) {
        StringBuilder sql = new StringBuilder("CASE");
        for (String[] slot : slots) {
            sql.append(" WHEN ").append(slot[0]).append(" = ").append(month).append(" AND ").append(slot[1])
                    .append(" IS NOT NULL THEN ").append(slot[1]);
        }
        return sql.append(" END").toString();
    }

    private static long insertCounts(CutoverDatabase db, String side, String periodMonth, String windowStart, String windowEnd,
                                     String providerTotal, String providerCountedAt, String imported, String stored,
                                     String knownGap, String importedCountedAt, String closedAt, String condition,
                                     Timestamp now) {
        String counts = FiscalDocumentTables.COUNTS;
        String sql = "INSERT INTO " + counts + " (id, company_id, channel, document_type, side, period_month, window_start,"
                + " window_end, provider_total, provider_counted_at, imported_total, stored_any_source_total, known_gap,"
                + " imported_counted_at, closed_at, created_by, created_date, last_modified_by, last_modified_date, deleted)"
                + " SELECT UUID(), c.company_id, '" + SyncControlCounters.CHANNEL + "', '" + SyncControlCounters.DOCUMENT_TYPE
                + "', '" + side + "', " + periodMonth + ", " + windowStart + ", " + windowEnd + ", " + providerTotal + ", "
                + providerCountedAt + ", COALESCE(" + imported + ", 0), " + stored + ", " + knownGap + ", " + importedCountedAt
                + ", " + closedAt + ", c.created_by, c.created_date, c.last_modified_by, c.last_modified_date, c.deleted"
                + " FROM " + SyncControlCounters.TABLE + " c WHERE " + condition
                + " AND NOT EXISTS (SELECT 1 FROM " + counts + " p WHERE p.company_id = c.company_id"
                + " AND p.channel = '" + SyncControlCounters.CHANNEL + "' AND p.document_type = '" + SyncControlCounters.DOCUMENT_TYPE
                + "' AND p.side = '" + side + "' AND p.period_month = " + periodMonth + ")";
        return now == null ? db.jdbc.update(sql) : db.jdbc.update(sql, now);
    }
}
