package br.com.cshub.taxify.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code routine_runs} (CE-RN-08 a): the daily reservation of a routine across instances. Plain SQL, each statement
 * on its own (auto-commit): the reservation must be visible to the other instance at once, never held by a long
 * transaction.
 */
@Repository
public class RoutineRunRepository {

    /** The row of the day of a routine. */
    public record Run(String id, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public RoutineRunRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** The INSERT the unique key (routine, run_date) lets through once; a duplicate key means another one has it. */
    public void insert(String id, String routine, LocalDate runDate, String instanceId, LocalDateTime startedAt) {
        jdbc.update("INSERT INTO routine_runs (id, routine, run_date, instance_id, started_at, deleted, created_by, created_date)"
                + " VALUES (:id, :routine, :runDate, :instance, :startedAt, 0, 'system', :startedAt)", new MapSqlParameterSource()
                .addValue("id", id).addValue("routine", routine).addValue("runDate", java.sql.Date.valueOf(runDate))
                .addValue("instance", instanceId).addValue("startedAt", Timestamp.valueOf(startedAt)));
    }

    public Optional<Run> find(String routine, LocalDate runDate) {
        List<Run> runs = jdbc.query("SELECT id, started_at, finished_at FROM routine_runs WHERE routine = :routine AND run_date = :runDate",
                new MapSqlParameterSource().addValue("routine", routine).addValue("runDate", java.sql.Date.valueOf(runDate)),
                (rs, i) -> new Run(rs.getString("id"), rs.getTimestamp("started_at").toLocalDateTime(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime()));
        return runs.stream().findFirst();
    }

    /** Takes over an unfinished run started before {@code staleBefore}: 1 = this instance has it now. */
    public int takeOver(String id, String instanceId, LocalDateTime startedAt, LocalDateTime staleBefore) {
        return jdbc.update("UPDATE routine_runs SET instance_id = :instance, started_at = :startedAt, last_modified_by = 'system',"
                + " last_modified_date = :startedAt WHERE id = :id AND finished_at IS NULL AND started_at < :staleBefore",
                new MapSqlParameterSource().addValue("id", id).addValue("instance", instanceId)
                        .addValue("startedAt", Timestamp.valueOf(startedAt)).addValue("staleBefore", Timestamp.valueOf(staleBefore)));
    }

    public int finish(String id, LocalDateTime finishedAt, int processed, int failed) {
        return jdbc.update("UPDATE routine_runs SET finished_at = :finishedAt, processed = :processed, failed = :failed,"
                + " last_modified_by = 'system', last_modified_date = :finishedAt WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("finishedAt", Timestamp.valueOf(finishedAt))
                        .addValue("processed", processed).addValue("failed", failed));
    }
}
