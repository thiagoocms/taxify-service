package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remodel of the fiscal documents (spec 2026-11-27, FD-RN-53, decision P-FD-03): drops the four {@code legacy_*}
 * tables - children first - and the counter columns of {@code fiscal_xml_sync_control} that V87 moved to
 * {@code capture_period_counts}. The backup of runbook step 1 (FD-RN-52) is the only way back after this.
 *
 * <p>Runs only after V88 (parity) succeeded: Flyway stops at the first failure, and this migration checks the history again
 * (V87 ran = V88 must have succeeded, also before dropping the counter columns). With legacy rows to drop it also needs
 * the SHA-256 of the checked backup ({@code FD_BACKUP_SHA256} or the Flyway placeholder {@code fd_backup_sha256}):
 * without it, it stops and the legacy_* tables stay. The value must be the SHA-256 of the last backup that
 * {@code backup.sh} checked and recorded in {@value #BACKUP_CHECKS_TABLE}, and the row counts recorded there must be
 * the ones of the legacy tables now (a typo, the hash of another file or a row written after the backup stop it too).
 * Checked before dropping anything. Idempotent ({@code DROP TABLE IF EXISTS}, a column only when it is
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
        requireCheckedBackup(db, sha);
        log.info("fiscal.documents.cutover.backup.sha256 prefix={}", sha.substring(0, 12));
    }

    /**
     * Written by {@code scripts/fiscal-documents/backup.sh} (DDL in {@code scripts/fiscal-documents/backup-checks.sql})
     * only after the dump was restored and checked: one row per dumped table with the SHA-256 of the file and the row
     * count of the source. Holds no client data.
     */
    static final String BACKUP_CHECKS_TABLE = "fiscal_documents_backup_checks";

    /**
     * The given SHA-256 must be the one of the last backup {@code backup.sh} checked on this database, and the rows the
     * four legacy tables hold now must be the ones it dumped - a row written after the backup would have no way back
     * once dropped. {@code fiscal_xml_sync_control} is recorded but not compared: it is not dropped, V87 copied every
     * row it had and V88 compared them, and after the rename the backup could no longer be taken again. A legacy table
     * already gone was dropped by an earlier run of this migration (V88 had passed), so it is not compared: that is
     * what lets a V89 that fell in the middle resume.
     */
    private static void requireCheckedBackup(CutoverDatabase db, String sha) {
        if (!db.tableExists(BACKUP_CHECKS_TABLE)) {
            throw refused("no backup was checked by backup.sh on this database (" + BACKUP_CHECKS_TABLE + " is missing)");
        }
        List<String> last = db.strings("SELECT backup_sha256 FROM " + BACKUP_CHECKS_TABLE + " ORDER BY id DESC LIMIT 1");
        if (last.isEmpty()) {
            throw refused("no backup was checked by backup.sh on this database (" + BACKUP_CHECKS_TABLE + " is empty)");
        }
        String recorded = last.getFirst();
        if (!sha.equals(recorded)) {
            log.error("fiscal.documents.cutover.backup.sha256.mismatch given={} recorded={}", sha.substring(0, 12),
                    recorded.substring(0, Math.min(12, recorded.length())));
            throw refused(BACKUP_VARIABLE + " is not the SHA-256 of the last backup checked by backup.sh");
        }
        Map<String, Long> dumped = new LinkedHashMap<>();
        for (Map<String, Object> row : db.jdbc.queryForList("SELECT table_name, row_count FROM " + BACKUP_CHECKS_TABLE
                + " WHERE backup_sha256 = ? ORDER BY id DESC", sha)) {
            dumped.putIfAbsent((String) row.get("table_name"), ((Number) row.get("row_count")).longValue());
        }
        Map<String, String> present = new LinkedHashMap<>();
        for (LegacyTable table : LegacyTable.values()) {
            if (db.tableExists(table.legacyName())) {
                present.put(table.originalName(), table.legacyName());
            }
        }
        List<String> divergent = new ArrayList<>();
        for (Map.Entry<String, String> table : present.entrySet()) {
            long now = db.count("SELECT COUNT(*) FROM " + table.getValue());
            Long backup = dumped.get(table.getKey());
            if (backup == null || backup != now) {
                log.error("fiscal.documents.cutover.backup.count.mismatch table={} backup={} now={}", table.getKey(),
                        backup == null ? "-" : backup, now);
                divergent.add(table.getKey());
            }
        }
        if (!divergent.isEmpty()) {
            throw refused("the rows of " + divergent + " are not the ones of the checked backup (see"
                    + " fiscal.documents.cutover.backup.count.mismatch) - take the backup again with every routine stopped");
        }
    }

    private static IllegalStateException refused(String reason) {
        return new IllegalStateException("V89 refused: " + reason + " (FD-RN-52); the legacy_* tables are kept");
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
