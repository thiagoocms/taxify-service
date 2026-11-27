package db.fiscaldocuments;

import org.flywaydb.core.api.migration.Context;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * What V86 to V89 of the fiscal documents remodel (spec 2026-11-27) share: the connection of the migration, the
 * {@code information_schema} lookups and the short metadata-lock wait.
 *
 * <p>Every DDL and copy runs with {@code lock_wait_timeout} at {@value #LOCK_WAIT_SECONDS} s: with the application
 * stopped (runbook, step 3) nothing holds the tables, so waiting longer only means something is still running against
 * them - the migration fails with a clear message instead of hanging the deploy. The session value is given back in
 * {@code finally}.
 */
final class CutoverDatabase {

    static final int LOCK_WAIT_SECONDS = 30;
    static final ZoneId RECIFE = ZoneId.of("America/Recife");

    final JdbcTemplate jdbc;

    CutoverDatabase(Context context) {
        this.jdbc = new JdbcTemplate(new SingleConnectionDataSource(context.getConnection(), true));
    }

    boolean tableExists(String table) {
        return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?", table) > 0;
    }

    boolean columnExists(String table, String column) {
        return count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?"
                + " AND column_name = ?", table, column) > 0;
    }

    /** The column itself when it exists, or {@code NULL} - the V85 counters may not be on this database yet. */
    String columnOrNull(String table, String alias, String column) {
        return columnExists(table, column) ? alias + "." + column : "NULL";
    }

    long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    List<String> strings(String sql, Object... args) {
        return jdbc.queryForList(sql, String.class, args);
    }

    /** The copy's own "now" (closed_at of the months it closes): Recife wall clock, like every date of the schema. */
    static LocalDateTime nowInRecife() {
        return LocalDateTime.now(RECIFE).withNano(0);
    }

    <T> T withShortLockWait(String step, Supplier<T> work) {
        Integer previous = jdbc.queryForObject("SELECT @@SESSION.lock_wait_timeout", Integer.class);
        try {
            jdbc.execute("SET SESSION lock_wait_timeout = " + LOCK_WAIT_SECONDS);
            return work.get();
        } catch (DataAccessException e) {
            throw new IllegalStateException("fiscal documents " + step + " failed (" + e.getClass().getSimpleName()
                    + "): if it was a lock wait timeout, something still uses the fiscal tables - stop every application"
                    + " instance (runbook step 3) and start again; the step is idempotent and resumes where it stopped", e);
        } finally {
            if (previous != null) {
                jdbc.execute("SET SESSION lock_wait_timeout = " + previous);
            }
        }
    }

    void withShortLockWait(String step, Runnable work) {
        withShortLockWait(step, () -> {
            work.run();
            return null;
        });
    }
}
