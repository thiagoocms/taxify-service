package db.migration;

import java.util.List;
import java.util.stream.Stream;

/**
 * The counter columns of {@code fiscal_xml_sync_control} that move to {@code capture_period_counts} (spec 3.11): V87
 * copies them, V88 compares them field by field and V89 drops them. The queue and routine dates stay ({@code id},
 * {@code company_id}, {@code last_attempt_at}, {@code *_events_checked_on}, {@code service_invoices_checked_on},
 * auditing and {@code deleted}).
 *
 * <p>The last ten come from V85 ({@code fix/sieg-call-savings}) and may not exist on a database that never ran it (the first version had six, the final one ten):
 * the copy reads them as {@code NULL} then.
 */
final class SyncControlCounters {

    static final String TABLE = "fiscal_xml_sync_control";
    static final String CHANNEL = "SIEG";
    static final String DOCUMENT_TYPE = "NFE";

    static final List<String> V19_V79 = List.of(
            "total_sieg", "total_importado", "ultima_verificacao_em",
            "issued_total_sieg", "issued_total_imported", "issued_checked_at",
            "data_emissao_inicio", "data_emissao_fim",
            "received_prev_month", "received_prev_total_sieg", "received_prev_imported",
            "issued_prev_month", "issued_prev_total_sieg", "issued_prev_imported");

    static final List<String> V85 = List.of(
            "received_known_gap_month", "received_known_gap",
            "issued_known_gap_month", "issued_known_gap",
            "received_earlier_gap_month", "received_earlier_gap",
            "issued_earlier_gap_month", "issued_earlier_gap",
            "received_stored_any_source", "issued_stored_any_source");

    /**
     * The two V85 slots of "known gap" per side ({@code <side>_<slot>_month} and {@code <side>_<slot>}): each holds one
     * month, and each becomes the {@code known_gap} of that month's row. Should both hold the same month, the first
     * slot wins.
     */
    static final List<String> GAP_SLOTS = List.of("known_gap", "earlier_gap");

    static final List<String> ALL = Stream.concat(V19_V79.stream(), V85.stream()).toList();

    private SyncControlCounters() {
    }

    /**
     * With V85 on the database (in the history, or any of its columns present), all ten columns must be there: a
     * missing one is an error, never a silent NULL. Without V85 at all, they read as NULL.
     */
    static void requireV85Complete(CutoverDatabase db) {
        if (!present(db)) {
            return;
        }
        List<String> missing = V85.stream().filter(column -> !db.columnExists(TABLE, column)).toList();
        boolean v85 = db.migrated("85") || missing.size() < V85.size();
        if (v85 && !missing.isEmpty()) {
            throw new IllegalStateException("fiscal documents cutover stopped before the rename: V85 is applied but "
                    + TABLE + " lacks " + missing);
        }
    }

    /** The counters are still on the table (V87 and V88 have work to do; V89 drops them). */
    static boolean present(CutoverDatabase db) {
        return db.tableExists(TABLE) && db.columnExists(TABLE, "total_sieg");
    }
}
