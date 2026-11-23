package br.com.cshub.taxify.service.pendency;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hourly (after the SEFAZ capture at :15) brings every active company's pendencies in line with
 * what's true now, on a virtual thread; a run still going isn't doubled. Also trims the capture
 * history to {@link #RUN_RETENTION_DAYS}.
 */
@Component
public class PendencyScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendencyScheduler.class);
    static final int RUN_RETENTION_DAYS = 90;

    private final CompanyRepository companyRepository;
    private final PendencyService service;
    private final CaptureRunRepository captureRunRepository;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PendencyScheduler(CompanyRepository companyRepository, PendencyService service,
                             CaptureRunRepository captureRunRepository,
                             @Value("${pendencies.enabled:true}") boolean enabled) {
        this.companyRepository = companyRepository;
        this.service = service;
        this.captureRunRepository = captureRunRepository;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${pendencies.cron:0 40 * * * *}")
    public void tick() {
        if (enabled) {
            runInBackground();
        }
    }

    /** @return false when a run is already going */
    public boolean runInBackground() {
        if (!running.compareAndSet(false, true)) {
            log.info("Pendencies still being generated from the previous run - skipped");
            return false;
        }
        Thread.ofVirtual().name("pendencies").start(() -> {
            try {
                refreshAll();
            } catch (RuntimeException e) {
                log.warn("Pendencies run failed", e);
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    void refreshAll() {
        List<Company> companies = companyRepository.findAll().stream()
                .filter(company -> !company.isDeleted() && !Boolean.FALSE.equals(company.getActive()))
                .toList();
        int open = 0;
        for (Company company : companies) {
            try {
                open += service.refresh(company);
            } catch (RuntimeException e) {
                log.warn("Pendencies failed for company {}", company.getId(), e);
            }
        }
        int trimmed = captureRunRepository.deleteOlderThan(LocalDateTime.now().minusDays(RUN_RETENTION_DAYS));
        log.info("Pendencies: {} companies, {} open; {} old capture runs removed", companies.size(), open, trimmed);
    }
}
