package br.com.cshub.taxify.service.routine;

import br.com.cshub.taxify.common.DuplicateKeys;
import br.com.cshub.taxify.repository.RoutineRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * One run per routine and day of Recife across instances (generic; spec 2026-11-27, F5a, CE-RN-08 a):
 * <ol>
 *   <li>{@link #reserve} inserts the day's row - the unique key lets only one instance in;</li>
 *   <li>the other one sees the duplicate and doesn't run, unless the row is unfinished and started more than
 *       {@code staleMinutes} ago: then it takes over with a conditional UPDATE (1 row = taken);</li>
 *   <li>{@link #finish} closes the row with the counts.</li>
 * </ol>
 * Each statement runs on its own (no surrounding transaction), so the other instance sees it at once.
 */
@Service
public class RoutineRunReservation {

    private static final Logger log = LoggerFactory.getLogger(RoutineRunReservation.class);

    /** A reserved run. {@code takenOver} = it was another instance's stale run. */
    public record Reservation(String id, String routine, LocalDate runDate, boolean takenOver) {
    }

    private final RoutineRunRepository repository;
    private final Clock clock;
    private final String instanceId;

    public RoutineRunReservation(RoutineRunRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        this.instanceId = resolveInstanceId();
    }

    /** @return the reservation, or empty when another instance has the day's run (and it isn't stale) */
    public Optional<Reservation> reserve(String routine, int staleMinutes) {
        LocalDateTime now = now();
        LocalDate runDate = now.toLocalDate();
        String id = UUID.randomUUID().toString();
        try {
            repository.insert(id, routine, runDate, instanceId, now);
            return Optional.of(new Reservation(id, routine, runDate, false));
        } catch (DataIntegrityViolationException e) {
            if (!DuplicateKeys.isDuplicateKey(e)) {
                throw e;
            }
        }
        return repository.find(routine, runDate)
                .filter(run -> run.finishedAt() == null)
                .filter(run -> repository.takeOver(run.id(), instanceId, now, now.minusMinutes(staleMinutes)) == 1)
                .map(run -> {
                    log.info("routine.run.taken.over routine={} runDate={} instanceId={}", routine, runDate, instanceId);
                    return new Reservation(run.id(), routine, runDate, true);
                });
    }

    public void finish(Reservation reservation, int processed, int failed) {
        try {
            repository.finish(reservation.id(), now(), processed, failed);
        } catch (RuntimeException e) {
            // the run itself is done; an unfinished row only lets another instance take over after the stale time
            log.warn("routine.run.finish.failed routine={} runDate={}: {}", reservation.routine(), reservation.runDate(),
                    e.getClass().getSimpleName());
        }
    }

    public String instanceId() {
        return instanceId;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    /** The JVM name (pid@host) cut to the column, or a random id - diagnosis only. */
    private static String resolveInstanceId() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        if (name == null || name.isBlank()) {
            name = UUID.randomUUID().toString();
        }
        return name.length() > 60 ? name.substring(0, 60) : name;
    }
}
