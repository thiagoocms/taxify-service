package db.fiscaldocuments;

import java.util.List;
import java.util.stream.Stream;

/**
 * The counter columns of {@code fiscal_xml_sync_control} that move to {@code capture_period_counts} (spec 3.11): V87
 * copies them, V88 compares them field by field and V89 drops them. The queue and routine dates stay ({@code id},
 * {@code company_id}, {@code last_attempt_at}, {@code *_events_checked_on}, {@code service_invoices_checked_on},
 * auditing and {@code deleted}).
 *
 * <p>The last six come from V85 ({@code fix/sieg-call-savings}) and may not exist on a database that never ran it:
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
            "received_stored_any_source", "issued_stored_any_source");

    static final List<String> ALL = Stream.concat(V19_V79.stream(), V85.stream()).toList();

    private SyncControlCounters() {
    }

    /** The counters are still on the table (V87 and V88 have work to do; V89 drops them). */
    static boolean present(CutoverDatabase db) {
        return db.tableExists(TABLE) && db.columnExists(TABLE, "total_sieg");
    }
}
