package br.com.cshub.taxify.dto.integration;

import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.enumerated.SyncStatus;

import java.time.LocalDateTime;

/**
 * Background job of one integration, as the screen polls it.
 *
 * @param status    null when the job never ran
 * @param processed items done so far / {@code total} - total is null until the job knows it
 * @param result    last successful run's summary (kept while a new run is RUNNING)
 * @param error     why the last run FAILED
 */
public record SyncJobStatusDTO<R>(
        IntegrationType type,
        SyncStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Integer processed,
        Integer total,
        R result,
        String error
) {
    public boolean running() {
        return status == SyncStatus.RUNNING;
    }
}
