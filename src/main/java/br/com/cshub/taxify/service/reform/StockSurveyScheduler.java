package br.com.cshub.taxify.service.reform;

import br.com.cshub.taxify.config.StockSurveyProperties;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyCalendar;
import br.com.cshub.taxify.repository.StockSurveySourceRepository;
import br.com.cshub.taxify.repository.StockSurveySourceRepository.CompanyRef;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.RoutineGate;
import br.com.cshub.taxify.service.routine.RoutineRunReservation;
import br.com.cshub.taxify.service.routine.RoutineRunReservation.Reservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The daily stock credit survey (spec 2026-11-27, F5a, CE-RN-07, CE-RN-08, CE-RN-12): only with
 * {@code TAXIFY_STOCK_SURVEY_ENABLED=true}, never from 01/07/2027 on. One run per day of Recife across instances
 * ({@link RoutineRunReservation}); an {@link AtomicBoolean} against overlap on the same instance. Every active company of
 * the firms the {@link RoutineGate} lets through (system context, no portfolio filter), in batches of
 * {@code TAXIFY_STOCK_SURVEY_BATCH_SIZE}. A failed batch is retried company by company, so one company never stops
 * the others. Logs with ids and counts only.
 */
@Component
public class StockSurveyScheduler {

    public static final String ROUTINE = "STOCK_SURVEY";
    private static final Logger log = LoggerFactory.getLogger(StockSurveyScheduler.class);

    /** What a run did: firms and companies covered, failures, firms skipped by the gate; empty = didn't run. */
    public record RunSummary(int firmCount, int companyCount, int failed, int skippedFirms) {
    }

    private final StockSurveyProperties properties;
    private final StockSurveySourceRepository sourceRepository;
    private final StockSurveyCalculator calculator;
    private final RoutineRunReservation reservation;
    private final FirmAccessService firmAccessService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public StockSurveyScheduler(StockSurveyProperties properties, StockSurveySourceRepository sourceRepository,
                                StockSurveyCalculator calculator, RoutineRunReservation reservation,
                                FirmAccessService firmAccessService, Clock clock) {
        this.properties = properties;
        this.sourceRepository = sourceRepository;
        this.calculator = calculator;
        this.reservation = reservation;
        this.firmAccessService = firmAccessService;
        this.clock = clock;
    }

    @Scheduled(cron = "${taxify.reform.stock-survey.cron:0 15 6 * * *}", zone = "America/Recife")
    public void tick() {
        runGuarded();
    }

    /**
     * The second attempt of the day ({@code TAXIFY_STOCK_SURVEY_RETRY_CRON}, 09:00 of Recife): the same reservation, so
     * it only runs when the day has no run yet (the 06:15 one didn't start - e.g. a deploy) or the run is stale
     * (started more than {@code TAXIFY_STOCK_SURVEY_STALE_MINUTES} ago and never finished). A finished day is skipped.
     */
    @Scheduled(cron = "${taxify.reform.stock-survey.retry-cron:0 0 9 * * *}", zone = "America/Recife")
    public void retryTick() {
        runGuarded();
    }

    private void runGuarded() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("stock.survey.run.failed error={}", e.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    /** One run of the day, if this instance gets it. */
    public Optional<RunSummary> runOnce() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate runDate = now.toLocalDate();
        if (!properties.isEnabled()) {
            log.info("stock.survey.run.skipped runDate={} reason=DISABLED", runDate);
            return Optional.empty();
        }
        if (StockSurveyCalendar.isClosed(now)) {
            log.info("stock.survey.run.skipped runDate={} reason=CLOSED", runDate);
            return Optional.empty();
        }
        Optional<Reservation> reserved = reservation.reserve(ROUTINE, properties.getStaleMinutes());
        if (reserved.isEmpty()) {
            log.info("stock.survey.run.skipped runDate={} reason=ALREADY_RESERVED", runDate);
            return Optional.empty();
        }
        log.info("stock.survey.run.start runDate={} instanceId={}", runDate, reservation.instanceId());
        long started = System.nanoTime();
        RunSummary summary = new RunSummary(0, 0, 0, 0);
        try {
            summary = calculateAll();
            return Optional.of(summary);
        } finally {
            reservation.finish(reserved.get(), summary.companyCount() - summary.failed(), summary.failed());
            log.info("stock.survey.run.end runDate={} firmCount={} companyCount={} failed={} durationMs={} skippedFirms={}",
                    runDate, summary.firmCount(), summary.companyCount(), summary.failed(),
                    (System.nanoTime() - started) / 1_000_000, summary.skippedFirms());
        }
    }

    private RunSummary calculateAll() {
        RoutineGate gate = firmAccessService.routineGate("stock-survey");
        List<CompanyRef> companies = sourceRepository.eligibleCompanies().stream()
                .filter(company -> gate.allowsFirm(company.firmId()))
                .toList();
        gate.logSkipped();
        int batchSize = Math.max(1, properties.getBatchSize());
        int failed = 0;
        for (int from = 0; from < companies.size(); from += batchSize) {
            failed += calculateBatch(companies.subList(from, Math.min(companies.size(), from + batchSize)));
        }
        long firms = companies.stream().map(CompanyRef::firmId).distinct().count();
        return new RunSummary((int) firms, companies.size(), failed, gate.skippedFirms());
    }

    /** @return companies that failed */
    private int calculateBatch(List<CompanyRef> batch) {
        try {
            return calculator.calculate(batch).failed();
        } catch (RuntimeException batchError) {
            if (batch.size() == 1) {
                log.warn("stock.survey.company.failed companyId={} error={}", batch.getFirst().companyId(),
                        batchError.getClass().getSimpleName());
                return 1;
            }
            int failed = 0;
            for (CompanyRef company : batch) {
                failed += calculateBatch(List.of(company));
            }
            return failed;
        }
    }
}
