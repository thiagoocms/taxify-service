package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, FD-RN-53, decision P-FD-03): drops the four {@code legacy_*}
 * tables - children first - and the counter columns of {@code fiscal_xml_sync_control} that V87 moved to
 * {@code capture_period_counts}. The backup of runbook step 1 (FD-RN-52) is the only way back after this.
 *
 * <p>Runs only after V88 (parity) succeeded: Flyway stops at the first failure, and this migration checks the history again
 * (V87 ran = V88 must have succeeded, also before dropping the counter columns). With legacy rows to drop it also needs
 * the SHA-256 of the checked backup ({@code FD_BACKUP_SHA256} or the Flyway placeholder {@code fd_backup_sha256}):
 * without it, it stops and the legacy_* tables stay. Checked
 * before dropping anything. Idempotent ({@code DROP TABLE IF EXISTS}, a column only when it is
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
            guard(db, context);
            dropLegacyTables(db);
            dropCounterColumns(db);
        });
    }

    /**
     * Placeholder ({@code spring.flyway.placeholders.fd_backup_sha256}) or environment variable
     * ({@code FD_BACKUP_SHA256}) with the SHA-256 of the backup of runbook step 1 (FD-RN-52), written down by
     * {@code scripts/fiscal-documents/backup.sh}. A technical lock: with legacy rows to drop and no valid value, V89
     * stops and the legacy_* tables stay.
     */
    static final String BACKUP_PLACEHOLDER = "fd_backup_sha256";
    static final String BACKUP_VARIABLE = "FD_BACKUP_SHA256";

    private void guard(CutoverDatabase db, Context context) {
        if (db.attempted("87") && !db.migrated("88")) {
            throw new IllegalStateException("V89 refused: V87 ran but V88 (parity) has not succeeded on this database -"
                    + " the legacy_* tables and the counter columns are kept");
        }
        if (legacyRows(db) == 0) {
            return;
        }
        if (!db.migrated("88")) {
            throw new IllegalStateException("V89 refused: V88 (parity) has not succeeded on this database - the legacy_*"
                    + " tables are kept");
        }
        String sha = backupSha256(context);
        if (sha == null || !sha.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("V89 refused: the SHA-256 of the checked backup (FD-RN-52) was not given - set "
                    + BACKUP_VARIABLE + " (or the Flyway placeholder " + BACKUP_PLACEHOLDER + ") to the value backup.sh wrote;"
                    + " the legacy_* tables are kept");
        }
        log.info("fiscal.documents.cutover.backup.sha256 prefix={}", sha.substring(0, 12));
    }

    private static String backupSha256(Context context) {
        String value = null;
        if (context.getConfiguration() != null && context.getConfiguration().getPlaceholders() != null) {
            value = context.getConfiguration().getPlaceholders().get(BACKUP_PLACEHOLDER);
        }
        if (value == null || value.isBlank()) {
            value = System.getenv(BACKUP_VARIABLE);
        }
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    private static long legacyRows(CutoverDatabase db) {
        long rows = 0;
        for (LegacyTable table : LegacyTable.values()) {
            if (db.tableExists(table.legacyName())) {
                rows += db.count("SELECT COUNT(*) FROM (SELECT 1 FROM " + table.legacyName() + " LIMIT 1) t");
            }
        }
        return rows;
    }

    private void dropLegacyTables(CutoverDatabase db) {
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
