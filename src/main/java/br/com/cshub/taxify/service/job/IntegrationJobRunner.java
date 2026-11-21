package br.com.cshub.taxify.service.job;

import br.com.cshub.taxify.config.IntegrationJobConfig;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.dto.integration.SyncJobStatusDTO;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.enumerated.SyncStatus;
import br.com.cshub.taxify.repository.FirmIntegrationRepository;
import br.com.cshub.taxify.service.FirmIntegrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Runs an integration's sync/import in the background and keeps its state in the firm's
 * FirmIntegration row, so the screen can start it and poll it:
 * <ul>
 *   <li>{@link #start} claims the job atomically in the database ({@code claimSync}) - a
 *   second click, the scheduler or another instance gets the running job's status back
 *   instead of starting a duplicate - then hands it to the job pool and returns at once;</li>
 *   <li>while it runs, a heartbeat keeps the claim alive and progress is flushed every few
 *   seconds; a job with no heartbeat for {@link #STALE_AFTER} (process died mid-run) can be
 *   claimed again;</li>
 *   <li>at the end, status + the result as JSON (or the error) are stored for {@link #status}.</li>
 * </ul>
 * The caller's security context goes along with the job, so whatever it creates is audited
 * as the user who clicked (scheduler-started jobs run as the system, as before).
 */
@Component
public class IntegrationJobRunner {

    private static final Logger log = LoggerFactory.getLogger(IntegrationJobRunner.class);

    static final Duration STALE_AFTER = Duration.ofMinutes(5);
    private static final Duration HEARTBEAT_EVERY = Duration.ofSeconds(60);
    private static final long PROGRESS_FLUSH_MILLIS = 2_000;
    private static final int ERROR_MAX = 1000;
    static final String ABANDONED_MESSAGE = "A execução foi interrompida (o servidor reiniciou no meio). Inicie novamente.";

    private final FirmIntegrationService firmIntegrationService;
    private final FirmIntegrationRepository repository;
    private final TaskExecutor executor;
    private final TaskScheduler scheduler;
    private final ObjectMapper objectMapper;

    public IntegrationJobRunner(FirmIntegrationService firmIntegrationService, FirmIntegrationRepository repository,
                                @Qualifier(IntegrationJobConfig.EXECUTOR) TaskExecutor executor, TaskScheduler scheduler,
                                ObjectMapper objectMapper) {
        this.firmIntegrationService = firmIntegrationService;
        this.repository = repository;
        this.executor = executor;
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    /**
     * Starts {@code job} for the firm unless one is already running; either way returns the
     * current status right away (RUNNING).
     */
    public <R> SyncJobStatusDTO<R> start(UUID accountingFirmId, IntegrationType type, Class<R> resultType,
                                         Function<SyncProgress, R> job) {
        UUID integrationId = firmIntegrationService.getOrCreate(accountingFirmId, type).getId();
        LocalDateTime now = LocalDateTime.now();
        boolean claimed = repository.claimSync(integrationId, now, now.minus(STALE_AFTER)) == 1;
        if (claimed) {
            executor.execute(new DelegatingSecurityContextRunnable(() -> execute(integrationId, type, job)));
        } else {
            log.info("{} job for firm {} already running - not starting another", type, accountingFirmId);
        }
        return status(accountingFirmId, type, resultType);
    }

    public <R> SyncJobStatusDTO<R> status(UUID accountingFirmId, IntegrationType type, Class<R> resultType) {
        return firmIntegrationService.find(accountingFirmId, type)
                .map(integration -> toStatus(integration, resultType))
                .orElseGet(() -> new SyncJobStatusDTO<>(type, null, null, null, null, null, null, null));
    }

    private <R> void execute(UUID integrationId, IntegrationType type, Function<SyncProgress, R> job) {
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> repository.touchSync(integrationId, LocalDateTime.now()), Instant.now().plus(HEARTBEAT_EVERY), HEARTBEAT_EVERY);
        ThrottledProgress progress = new ThrottledProgress(integrationId);
        try {
            R result = job.apply(progress);
            progress.flush();
            repository.completeSync(integrationId, SyncStatus.SUCCEEDED, toJson(result), null, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("{} job {} failed", type, integrationId, e);
            progress.flush();
            repository.completeSync(integrationId, SyncStatus.FAILED, null, truncate(e.getMessage()), LocalDateTime.now());
        } finally {
            heartbeat.cancel(false);
        }
    }

    private <R> SyncJobStatusDTO<R> toStatus(FirmIntegration integration, Class<R> resultType) {
        // RUNNING without a recent heartbeat = the process died mid-run (deploy, restart): report
        // it as failed, so the screen stops waiting and the next start is allowed (see claimSync).
        boolean abandoned = integration.getSyncStatus() == SyncStatus.RUNNING && isStale(integration.getSyncHeartbeatAt());
        return new SyncJobStatusDTO<>(
                integration.getType(),
                abandoned ? SyncStatus.FAILED : integration.getSyncStatus(),
                integration.getSyncStartedAt(),
                integration.getSyncFinishedAt(),
                integration.getSyncProcessed(),
                integration.getSyncTotal(),
                fromJson(integration.getSyncResult(), resultType),
                abandoned ? ABANDONED_MESSAGE : integration.getSyncError());
    }

    private static boolean isStale(LocalDateTime heartbeat) {
        return heartbeat == null || heartbeat.isBefore(LocalDateTime.now().minus(STALE_AFTER));
    }

    private String toJson(Object result) {
        try {
            return result == null ? null : objectMapper.writeValueAsString(result);
        } catch (JacksonException e) {
            log.warn("Could not serialize job result {}", result.getClass().getSimpleName(), e);
            return null;
        }
    }

    private <R> R fromJson(String json, Class<R> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException e) {
            // e.g. the result type changed between versions - the summary is just not shown
            log.debug("Stored job result is not a {}: {}", type.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= ERROR_MAX ? message : message.substring(0, ERROR_MAX);
    }

    /** Counts in memory; writes to the database at most every {@link #PROGRESS_FLUSH_MILLIS}. */
    private final class ThrottledProgress implements SyncProgress {
        private final UUID integrationId;
        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger total = new AtomicInteger(-1);
        private final AtomicLong lastFlush = new AtomicLong();

        private ThrottledProgress(UUID integrationId) {
            this.integrationId = integrationId;
        }

        @Override
        public void total(int value) {
            total.set(value);
            flush();
        }

        @Override
        public void advance() {
            processed.incrementAndGet();
            long now = System.currentTimeMillis();
            long last = lastFlush.get();
            if (now - last >= PROGRESS_FLUSH_MILLIS && lastFlush.compareAndSet(last, now)) {
                flush();
            }
        }

        void flush() {
            int knownTotal = total.get();
            repository.updateSyncProgress(integrationId, processed.get(), knownTotal < 0 ? null : knownTotal, LocalDateTime.now());
        }
    }
}
