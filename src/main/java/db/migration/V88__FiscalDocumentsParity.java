package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, FD-RN-25): old × new, zero tolerance, before V89 drops the old
 * tables. Each block is the same aggregate on both sides, grouped by the same key; a group missing on one side or with
 * any different value is a difference:
 * <ul>
 *   <li><b>ids</b>: every old id of documents, NFS-e, items and events is in the new table, and the new tables have no
 *   other id (FD-RN-20);</li>
 *   <li><b>goods</b> (NF-e, NFC-e, CT-e) by company × month of {@code issue_date} (no date = its own bucket) × type ×
 *   direction: count, canceled, deleted, total, ICMS, ICMS-ST, IPI, PIS, COFINS, the five withholdings, IBS and CBS
 *   (ICMS-ST, IPI, PIS and COFINS don't exist for the CT-e, copied with the common block plus the ICMS, FD-RN-15);</li>
 *   <li><b>NFS-e</b> by company × month × direction: count, canceled, deleted, service, ISS, withheld ISS, IRRF, PIS,
 *   COFINS, CSLL, INSS, net, ISS base, deductions and the two withheld flags;</li>
 *   <li><b>items</b> by company × month × trimmed CFOP: count, total, ICMS, ICMS-ST, IPI, PIS, COFINS, IBS, CBS;</li>
 *   <li><b>events</b> by company × event type × registered: count and count linked to a document;</li>
 *   <li><b>sources</b> by company × source: documents with the old date filled (or uploaded) × rows, and the minimum and
 *   maximum date;</li>
 *   <li><b>capture counts</b>: each {@code fiscal_xml_sync_control} row × its {@code capture_period_counts} rows, field
 *   by field (expected rows built here, independently of the copy's SQL).</li>
 * </ul>
 * Any difference: log {@code fiscal.documents.cutover.parity.mismatch} per block (count and up to
 * {@value #MAX_LOGGED} group keys - company id, month, type - never the values) and the migration fails, so V89 never
 * runs and the {@code legacy_*} tables stay. All equal: log {@code fiscal.documents.cutover.parity.ok} with the number
 * of groups per block. Without {@code legacy_*} (a new database) only the capture counts are compared.
 */
public class V88__FiscalDocumentsParity extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V88__FiscalDocumentsParity.class);
    static final int MAX_LOGGED = 20;

    private static final String FD = FiscalDocumentTables.DOCUMENTS;
    private static final String FDI = FiscalDocumentTables.ITEMS;
    private static final String FDE = FiscalDocumentTables.EVENTS;
    private static final String FDS = FiscalDocumentTables.SOURCES;
    private static final String MONTH_OF = "DATE_FORMAT(%s.issue_date, '%%Y-%%m')";

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) {
        CutoverDatabase db = new CutoverDatabase(context);
        Map<String, Integer> groups = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        boolean documents = db.tableExists(LegacyTable.DOCUMENTS.legacyName());
        boolean invoices = db.tableExists(LegacyTable.SERVICE_INVOICES.legacyName());
        boolean items = db.tableExists(LegacyTable.ITEMS.legacyName());
        boolean events = db.tableExists(LegacyTable.EVENTS.legacyName());
        if (!documents && !invoices && !items && !events) {
            log.info("fiscal.documents.cutover.nothing.to.do reason=no-legacy-tables step=parity");
        } else {
            ids(db, documents, invoices, items, events, groups, failed);
            if (documents) {
                compare(db, "goods", goodsLegacy(), goodsNew(), groups, failed);
            }
            if (invoices) {
                compare(db, "service-invoices", serviceInvoicesLegacy(), serviceInvoicesNew(), groups, failed);
            }
            if (items && documents) {
                compare(db, "items", itemsLegacy(), itemsNew(), groups, failed);
            }
            if (events) {
                compare(db, "events", eventsLegacy(), eventsNew(), groups, failed);
            }
            if (documents || invoices) {
                compare(db, "sources", sourcesLegacy(db, documents, invoices), sourcesNew(), groups, failed);
            }
        }
        SyncControlCounters.requireV85Complete(db);
        if (SyncControlCounters.present(db)) {
            captureCounts(db, groups, failed);
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("fiscal documents parity failed in " + failed
                    + " - see fiscal.documents.cutover.parity.mismatch; V89 will not run and the legacy_* tables are intact");
        }
        log.info("fiscal.documents.cutover.parity.ok groups={}", groups);
    }

    // ------------------------------------------------------------------ ids

    private void ids(CutoverDatabase db, boolean documents, boolean invoices, boolean items, boolean events,
                     Map<String, Integer> groups, List<String> failed) {
        List<String> differences = new ArrayList<>();
        List<String> oldDocuments = new ArrayList<>();
        if (documents) {
            oldDocuments.add("SELECT id FROM " + LegacyTable.DOCUMENTS.legacyName());
            missing(db, differences, "documents", LegacyTable.DOCUMENTS.legacyName(), FD);
        }
        if (invoices) {
            oldDocuments.add("SELECT id FROM " + LegacyTable.SERVICE_INVOICES.legacyName());
            missing(db, differences, "service-invoices", LegacyTable.SERVICE_INVOICES.legacyName(), FD);
        }
        long extra = db.count("SELECT COUNT(*) FROM " + FD + " n WHERE NOT EXISTS (SELECT 1 FROM ("
                + String.join(" UNION ALL ", oldDocuments.isEmpty() ? List.of("SELECT NULL AS id") : oldDocuments)
                + ") o WHERE o.id = n.id)");
        if (extra > 0) {
            differences.add("documents.extra=" + extra);
        }
        if (items) {
            missing(db, differences, "items", LegacyTable.ITEMS.legacyName(), FDI);
            missing(db, differences, "items.extra", FDI, LegacyTable.ITEMS.legacyName());
        }
        if (events) {
            missing(db, differences, "events", LegacyTable.EVENTS.legacyName(), FDE);
            missing(db, differences, "events.extra", FDE, LegacyTable.EVENTS.legacyName());
        }
        groups.put("ids", 4);
        if (!differences.isEmpty()) {
            log.error("fiscal.documents.cutover.parity.mismatch block=ids groups={} differences={}", differences.size(), differences);
            failed.add("ids");
        }
    }

    private static void missing(CutoverDatabase db, List<String> differences, String what, String from, String in) {
        long missing = db.count("SELECT COUNT(*) FROM " + from + " o WHERE NOT EXISTS (SELECT 1 FROM " + in + " n WHERE n.id = o.id)");
        if (missing > 0) {
            differences.add(what + ".missing=" + missing);
        }
    }

    // ------------------------------------------------------------------ aggregates

    /** Key columns first ({@code k_*}), then the values; one row per group. */
    private record Aggregate(String sql, int keys) {
    }

    private static String month(String alias) {
        return String.format(MONTH_OF, alias);
    }

    private static Aggregate goodsLegacy() {
        String type = CopyMappings.GOODS_DOCUMENT_TYPE;
        String goods = "CASE WHEN l.document_type = 'CTE' THEN NULL ELSE %s END";
        return new Aggregate("SELECT l.company_id, " + month("l") + ", " + type + ", " + CopyMappings.DERIVED_DIRECTION + ","
                + " COUNT(*), SUM(CASE WHEN l.status = 'CANCELED' THEN 1 ELSE 0 END), SUM(CASE WHEN l.status = 'CANCELED' AND l.canceled_at IS NOT NULL THEN 1 ELSE 0 END), SUM(CASE WHEN l.deleted = 1 THEN 1 ELSE 0 END),"
                + " SUM(l.total_valor_total_nota),"
                + " SUM(l.total_valor_icms), SUM(" + String.format(goods, "l.total_valor_icms_st") + "),"
                + " SUM(" + String.format(goods, "l.total_valor_ipi") + "), SUM(" + String.format(goods, "l.total_valor_pis") + "),"
                + " SUM(" + String.format(goods, "l.total_valor_cofins") + "),"
                + " SUM(l.ret_pis), SUM(l.ret_cofins), SUM(l.ret_csll), SUM(l.ret_irrf), SUM(l.ret_inss),"
                + " SUM(l.total_valor_ibs), SUM(l.total_valor_cbs), SUM(CASE WHEN l.own_issue = 1 THEN 1 ELSE 0 END)"
                + " FROM " + LegacyTable.DOCUMENTS.legacyName() + " l GROUP BY 1, 2, 3, 4", 4);
    }

    private static Aggregate goodsNew() {
        return new Aggregate("SELECT n.company_id, " + month("n") + ", n.document_type, n.direction,"
                + " COUNT(*), SUM(CASE WHEN n.status = 'CANCELED' THEN 1 ELSE 0 END), SUM(CASE WHEN n.canceled_at IS NOT NULL THEN 1 ELSE 0 END), SUM(CASE WHEN n.deleted = 1 THEN 1 ELSE 0 END),"
                + " SUM(n.total_amount), SUM(n.icms_amount), SUM(n.icms_st_amount), SUM(n.ipi_amount), SUM(n.pis_amount),"
                + " SUM(n.cofins_amount), SUM(n.withheld_pis_amount), SUM(n.withheld_cofins_amount),"
                + " SUM(n.withheld_csll_amount), SUM(n.withheld_irrf_amount), SUM(n.withheld_inss_amount),"
                + " SUM(n.ibs_amount), SUM(n.cbs_amount), SUM(CASE WHEN n.company_role = 'ISSUER' THEN 1 ELSE 0 END)"
                + " FROM " + FD + " n WHERE n.document_type IN ('NFE', 'NFCE', 'CTE') GROUP BY 1, 2, 3, 4", 4);
    }

    private static Aggregate serviceInvoicesLegacy() {
        return new Aggregate("SELECT l.company_id, " + month("l") + ", 'NFSE',"
                + " CASE l.direction WHEN 'PRESTADA' THEN 'OUTBOUND' WHEN 'TOMADA' THEN 'INBOUND' END,"
                + " COUNT(*), SUM(CASE WHEN l.canceled = 1 THEN 1 ELSE 0 END), SUM(CASE WHEN l.deleted = 1 THEN 1 ELSE 0 END), SUM(l.valor_servico), SUM(l.valor_iss),"
                + " SUM(l.valor_iss_retido), SUM(l.valor_irrf), SUM(l.valor_pis), SUM(l.valor_cofins), SUM(l.valor_csll),"
                + " SUM(l.valor_inss), SUM(l.valor_liquido), SUM(l.base_calculo), SUM(l.valor_deducoes),"
                + " SUM(CASE WHEN l.iss_retido = 1 THEN 1 ELSE 0 END), SUM(CASE WHEN l.pis_cofins_retidos = 1 THEN 1 ELSE 0 END), SUM(CASE WHEN l.direction = 'TERCEIRO' THEN 1 ELSE 0 END)"
                + " FROM " + LegacyTable.SERVICE_INVOICES.legacyName() + " l GROUP BY 1, 2, 3, 4", 4);
    }

    private static Aggregate serviceInvoicesNew() {
        return new Aggregate("SELECT n.company_id, " + month("n") + ", n.document_type, n.direction,"
                + " COUNT(*), SUM(CASE WHEN n.status = 'CANCELED' THEN 1 ELSE 0 END), SUM(CASE WHEN n.deleted = 1 THEN 1 ELSE 0 END), SUM(n.total_amount), SUM(n.iss_amount),"
                + " SUM(n.withheld_iss_amount), SUM(n.withheld_irrf_amount), SUM(n.pis_amount), SUM(n.cofins_amount),"
                + " SUM(n.withheld_csll_amount), SUM(n.withheld_inss_amount), SUM(n.nfse_net_amount), SUM(n.iss_base),"
                + " SUM(n.nfse_deductions_amount), SUM(CASE WHEN n.iss_withheld = 1 THEN 1 ELSE 0 END), SUM(CASE WHEN n.nfse_pis_cofins_withheld = 1 THEN 1 ELSE 0 END),"
                + " SUM(CASE WHEN n.company_role = 'THIRD_PARTY' THEN 1 ELSE 0 END)"
                + " FROM " + FD + " n WHERE n.document_type = 'NFSE' GROUP BY 1, 2, 3, 4", 4);
    }

    private static Aggregate itemsLegacy() {
        return new Aggregate("SELECT d.company_id, " + month("d") + ", TRIM(i.cfop),"
                + " COUNT(*), SUM(i.valor_total), SUM(i.valor_icms), SUM(i.valor_icms_st), SUM(i.valor_ipi),"
                + " SUM(i.valor_pis), SUM(i.valor_cofins), SUM(i.ibs_valor), SUM(i.cbs_valor)"
                + " FROM " + LegacyTable.ITEMS.legacyName() + " i JOIN " + LegacyTable.DOCUMENTS.legacyName()
                + " d ON d.id = i.document_id GROUP BY 1, 2, 3", 3);
    }

    private static Aggregate itemsNew() {
        return new Aggregate("SELECT d.company_id, " + month("d") + ", i.cfop,"
                + " COUNT(*), SUM(i.total_amount), SUM(i.icms_amount), SUM(i.icms_st_amount), SUM(i.ipi_amount),"
                + " SUM(i.pis_amount), SUM(i.cofins_amount), SUM(i.ibs_amount), SUM(i.cbs_amount)"
                + " FROM " + FDI + " i JOIN " + FD + " d ON d.id = i.document_id"
                + " WHERE d.document_type IN ('NFE', 'NFCE', 'CTE') GROUP BY 1, 2, 3", 3);
    }

    private static Aggregate eventsLegacy() {
        return new Aggregate("SELECT e.company_id, e.event_type, e.registered = 1, COUNT(*), SUM(CASE WHEN e.document_id IS NOT NULL THEN 1 ELSE 0 END)"
                + " FROM " + LegacyTable.EVENTS.legacyName() + " e GROUP BY 1, 2, 3", 3);
    }

    private static Aggregate eventsNew() {
        return new Aggregate("SELECT e.company_id, e.event_type, e.registered = 1, COUNT(*), SUM(CASE WHEN e.document_id IS NOT NULL THEN 1 ELSE 0 END)"
                + " FROM " + FDE + " e GROUP BY 1, 2, 3", 3);
    }

    private static Aggregate sourcesLegacy(CutoverDatabase db, boolean documents, boolean invoices) {
        List<String> parts = new ArrayList<>();
        if (documents) {
            sourcePart(db, parts, LegacyTable.DOCUMENTS.legacyName(), "found_in_sieg_at", "SIEG", "PERIOD");
            sourcePart(db, parts, LegacyTable.DOCUMENTS.legacyName(), "found_in_sefaz_at", "SEFAZ_DFE", "CURSOR");
            sourcePart(db, parts, LegacyTable.DOCUMENTS.legacyName(), null, "UPLOAD", "NONE");
        }
        if (invoices) {
            sourcePart(db, parts, LegacyTable.SERVICE_INVOICES.legacyName(), "found_in_sieg_at", "SIEG", "PERIOD");
            sourcePart(db, parts, LegacyTable.SERVICE_INVOICES.legacyName(), "found_in_adn_at", "NFSE_ADN", "CURSOR");
            sourcePart(db, parts, LegacyTable.SERVICE_INVOICES.legacyName(), null, "UPLOAD", "NONE");
        }
        return new Aggregate("SELECT s.company_id, s.source, s.mode, COUNT(*), MIN(s.seen), MAX(s.seen), MIN(s.seen), MAX(s.seen)"
                + " FROM (" + String.join(" UNION ALL ", parts) + ") s GROUP BY 1, 2, 3", 3);
    }

    private static void sourcePart(CutoverDatabase db, List<String> parts, String table, String column, String source,
                                   String mode) {
        if (column != null && !db.columnExists(table, column)) {
            return;
        }
        String seen = column == null ? "created_date" : column;
        String condition = column == null ? "source_system = 'UPLOAD'" : column + " IS NOT NULL";
        parts.add("SELECT company_id, '" + source + "' AS source, '" + mode + "' AS mode, " + seen + " AS seen FROM " + table
                + " WHERE " + condition);
    }

    private static Aggregate sourcesNew() {
        return new Aggregate("SELECT s.company_id, s.source, s.retrieval_mode, COUNT(*), MIN(s.first_seen_at), MAX(s.first_seen_at),"
                + " MIN(s.last_seen_at), MAX(s.last_seen_at) FROM " + FDS + " s GROUP BY 1, 2, 3", 3);
    }

    private void compare(CutoverDatabase db, String block, Aggregate legacy, Aggregate current,
                         Map<String, Integer> groups, List<String> failed) {
        Map<List<Object>, List<Object>> before = load(db, legacy);
        Map<List<Object>, List<Object>> after = load(db, current);
        Set<List<Object>> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<List<Object>> divergent = new ArrayList<>();
        for (List<Object> key : keys) {
            if (!sameValues(before.get(key), after.get(key))) {
                divergent.add(key);
            }
        }
        groups.put(block, keys.size());
        report(block, divergent, failed);
    }

    private static Map<List<Object>, List<Object>> load(CutoverDatabase db, Aggregate aggregate) {
        Map<List<Object>, List<Object>> rows = new LinkedHashMap<>();
        db.jdbc.query(aggregate.sql(), row -> {
            ResultSetMetaData meta = row.getMetaData();
            List<Object> key = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (int column = 1; column <= meta.getColumnCount(); column++) {
                Object value = normalize(row.getObject(column));
                (column <= aggregate.keys() ? key : values).add(value);
            }
            rows.put(key, values);
        });
        return rows;
    }

    /** Numbers compared by value ({@code 10.00} = {@code 10}, a count = a sum of booleans), the rest as text. */
    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        if (value instanceof Boolean bool) {
            return bool ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return value.toString();
    }

    private static boolean sameValues(List<Object> before, List<Object> after) {
        if (before == null || after == null) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            Object a = before.get(i);
            Object b = after.get(i);
            if (a instanceof BigDecimal x && b instanceof BigDecimal y ? x.compareTo(y) != 0 : !Objects.equals(a, b)) {
                return false;
            }
        }
        return before.size() == after.size();
    }

    private static void report(String block, List<List<Object>> divergent, List<String> failed) {
        if (divergent.isEmpty()) {
            return;
        }
        log.error("fiscal.documents.cutover.parity.mismatch block={} groups={} keys={}", block, divergent.size(),
                divergent.subList(0, Math.min(MAX_LOGGED, divergent.size())));
        failed.add(block);
    }

    // ------------------------------------------------------------------ capture counts

    /** One expected {@code capture_period_counts} row (closed_at compared as "filled or not": its value is the copy's time). */
    private record CountRow(Object windowStart, Object windowEnd, Object providerTotal, Object providerCountedAt,
                            Object importedTotal, Object storedAnySource, Object knownGap, Object importedCountedAt,
                            boolean closed) {
        CountRow withKnownGap(Object gap) {
            return new CountRow(windowStart, windowEnd, providerTotal, providerCountedAt, importedTotal, storedAnySource, gap,
                    importedCountedAt, closed);
        }
    }

    private void captureCounts(CutoverDatabase db, Map<String, Integer> groups, List<String> failed) {
        String t = SyncControlCounters.TABLE;
        StringBuilder select = new StringBuilder("SELECT c.company_id");
        for (String column : SyncControlCounters.ALL) {
            select.append(", ").append(db.columnOrNull(t, "c", column)).append(" AS ").append(column);
        }
        Map<List<Object>, CountRow> expected = new LinkedHashMap<>();
        db.jdbc.query(select + " FROM " + t + " c", row -> {
            Map<String, Object> c = new LinkedHashMap<>();
            for (String column : SyncControlCounters.ALL) {
                c.put(column, normalize(row.getObject(column)));
            }
            expectedRows(row.getString("company_id"), c, expected);
        });

        Map<List<Object>, CountRow> actual = new LinkedHashMap<>();
        db.jdbc.query("SELECT company_id, side, period_month, window_start, window_end, provider_total, provider_counted_at,"
                + " imported_total, stored_any_source_total, known_gap, imported_counted_at, closed_at FROM "
                + FiscalDocumentTables.COUNTS + " WHERE channel = ? AND document_type = ?", row -> {
            actual.put(List.of(row.getString(1), row.getString(2), row.getString(3)), new CountRow(
                    normalize(row.getObject(4)), normalize(row.getObject(5)), normalize(row.getObject(6)),
                    normalize(row.getObject(7)), normalize(row.getObject(8)), normalize(row.getObject(9)),
                    normalize(row.getObject(10)), normalize(row.getObject(11)), row.getObject(12) != null));
        }, SyncControlCounters.CHANNEL, SyncControlCounters.DOCUMENT_TYPE);

        Set<List<Object>> keys = new LinkedHashSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        List<List<Object>> divergent = new ArrayList<>();
        for (List<Object> key : keys) {
            if (!sameCountRow(expected.get(key), actual.get(key))) {
                divergent.add(key);
            }
        }
        groups.put("capture-counts", keys.size());
        report("capture-counts", divergent, failed);
    }

    /**
     * The rows 3.11 asks for, per side: current month (window start; the issued side only when it was ever checked),
     * previous month (V79, closed) unless it is the current one, and the known gap (V85) on its month's row or on a
     * row of its own (closed unless it is the current month). A gap without month is not copied.
     */
    static void expectedRows(String companyId, Map<String, Object> c, Map<List<Object>, CountRow> expected) {
        Object windowStart = c.get("data_emissao_inicio");
        String currentMonth = windowStart == null ? null : windowStart.toString().substring(0, 7);
        for (String side : List.of("RECEIVED", "ISSUED")) {
            boolean received = side.equals("RECEIVED");
            String prefix = received ? "received" : "issued";
            Map<String, CountRow> bySide = new LinkedHashMap<>();
            Object providerTotal = c.get(received ? "total_sieg" : "issued_total_sieg");
            Object imported = c.get(received ? "total_importado" : "issued_total_imported");
            Object checkedAt = c.get(received ? "ultima_verificacao_em" : "issued_checked_at");
            Object stored = c.get(prefix + "_stored_any_source");
            boolean everChecked = received || providerTotal != null || imported != null || checkedAt != null;
            if (currentMonth != null && everChecked) {
                bySide.put(currentMonth, new CountRow(windowStart, c.get("data_emissao_fim"), providerTotal, checkedAt,
                        zeroIfNull(imported), stored, null, checkedAt, false));
            }
            String prevMonth = c.get(prefix + "_prev_month") == null ? "" : c.get(prefix + "_prev_month").toString().strip();
            if (!prevMonth.isEmpty() && !prevMonth.equals(currentMonth)) {
                bySide.put(prevMonth, new CountRow(null, null, c.get(prefix + "_prev_total_sieg"), null,
                        zeroIfNull(c.get(prefix + "_prev_imported")), null, null, null, true));
            }
            for (String slot : SyncControlCounters.GAP_SLOTS) {
                Object gapMonth = c.get(prefix + "_" + slot + "_month");
                Object gap = c.get(prefix + "_" + slot);
                if (gapMonth == null || gap == null) {
                    continue;
                }
                CountRow existing = bySide.get(gapMonth.toString());
                if (existing == null) {
                    bySide.put(gapMonth.toString(), new CountRow(null, null, null, null, BigDecimal.ZERO, null, gap, null,
                            !gapMonth.toString().equals(currentMonth)));
                } else if (existing.knownGap() == null) {
                    bySide.put(gapMonth.toString(), existing.withKnownGap(gap));
                }
            }
            bySide.forEach((month, row) -> expected.put(List.of(companyId, side, month), row));
        }
    }

    private static Object zeroIfNull(Object value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean sameCountRow(CountRow expected, CountRow actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return sameValues(List.of(Objects.toString(expected.windowStart(), ""), Objects.toString(expected.windowEnd(), ""),
                        orEmpty(expected.providerTotal()), Objects.toString(expected.providerCountedAt(), ""), orEmpty(expected.importedTotal()),
                        orEmpty(expected.storedAnySource()), orEmpty(expected.knownGap()),
                        Objects.toString(expected.importedCountedAt(), ""), expected.closed()),
                List.of(Objects.toString(actual.windowStart(), ""), Objects.toString(actual.windowEnd(), ""),
                        orEmpty(actual.providerTotal()), Objects.toString(actual.providerCountedAt(), ""), orEmpty(actual.importedTotal()),
                        orEmpty(actual.storedAnySource()), orEmpty(actual.knownGap()),
                        Objects.toString(actual.importedCountedAt(), ""), actual.closed()));
    }

    private static Object orEmpty(Object value) {
        return value == null ? "" : value;
    }
}
