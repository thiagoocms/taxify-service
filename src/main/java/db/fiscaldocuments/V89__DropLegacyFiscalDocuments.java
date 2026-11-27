package db.fiscaldocuments;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, FD-RN-53, decision P-FD-03): drops the four {@code legacy_*}
 * tables - children first - and the counter columns of {@code fiscal_xml_sync_control} that V87 moved to
 * {@code capture_period_counts}. The backup of runbook step 1 (FD-RN-52) is the only way back after this.
 *
 * <p>Runs only after V88 (parity) succeeded: Flyway stops at the first failure, and this migration checks it again in
 * the schema history before dropping anything. Idempotent ({@code DROP TABLE IF EXISTS}, a column only when it is
 * there). On a database without the old tables nothing is dropped but the counter columns, when present - the new
 * {@code FiscalXmlSyncControl} no longer maps them, and {@code total_sieg}/{@code total_importado}/
 * {@code ultima_verificacao_em} are {@code NOT NULL} without default, so leaving them would break its inserts.
 * Columns go with {@code ALGORITHM=INSTANT} (MySQL 8.0.29+), else {@code INPLACE}, else the server's default (the
 * table has one row per company).
 */
public class V89__DropLegacyFiscalDocuments extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V89__DropLegacyFiscalDocuments.class);

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) {
        CutoverDatabase db = new CutoverDatabase(context);
        db.withShortLockWait("V89 drop legacy", () -> {
            dropLegacyTables(db, context);
            dropCounterColumns(db);
        });
    }

    private void dropLegacyTables(CutoverDatabase db, Context context) {
        List<String> present = new ArrayList<>();
        for (LegacyTable table : LegacyTable.values()) {
            if (db.tableExists(table.legacyName())) {
                present.add(table.legacyName());
            }
        }
        if (present.isEmpty()) {
            log.info("fiscal.documents.cutover.nothing.to.do reason=no-legacy-tables step=drop");
            return;
        }
        String history = context.getConfiguration().getTable();
        if (db.count("SELECT COUNT(*) FROM " + history + " WHERE version = '88' AND success = 1") == 0) {
            throw new IllegalStateException("V89 refused: V88 (parity) has not succeeded on this database - the legacy_*"
                    + " tables are kept");
        }
        for (LegacyTable table : LegacyTable.values()) {
            db.jdbc.execute("DROP TABLE IF EXISTS " + table.legacyName());
        }
        log.info("fiscal.documents.cutover.legacy.dropped tables={}", present.size());
    }

    private void dropCounterColumns(CutoverDatabase db) {
        if (!db.tableExists(SyncControlCounters.TABLE)) {
            return;
        }
        List<String> drops = new ArrayList<>();
        for (String column : SyncControlCounters.ALL) {
            if (db.columnExists(SyncControlCounters.TABLE, column)) {
                drops.add("DROP COLUMN " + column);
            }
        }
        if (drops.isEmpty()) {
            log.info("fiscal.documents.cutover.counters skipped=already-dropped");
            return;
        }
        String alter = "ALTER TABLE " + SyncControlCounters.TABLE + " " + String.join(", ", drops);
        for (String algorithm : List.of(", ALGORITHM=INSTANT", ", ALGORITHM=INPLACE", "")) {
            try {
                db.jdbc.execute(alter + algorithm);
                log.info("fiscal.documents.cutover.counters.dropped columns={} algorithm={}", drops.size(),
                        algorithm.isEmpty() ? "DEFAULT" : algorithm.substring(algorithm.indexOf('=') + 1));
                return;
            } catch (DataAccessException e) {
                if (algorithm.isEmpty()) {
                    throw e;
                }
                log.info("fiscal.documents.cutover.counters algorithm={} refused ({}), trying the next",
                        algorithm.substring(algorithm.indexOf('=') + 1), e.getClass().getSimpleName());
            }
        }
    }
}
