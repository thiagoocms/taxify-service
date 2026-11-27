package br.com.cshub.taxify.service.taxclearance;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.RoutineGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The certidão SEFAZ-PE on its own: once a week ({@code efisco.tax-clearance.cron}, formerly {@code efisco.certidao.cron}; Monday 6h by default) every PE company
 * without a certidão, or whose certidão expires in {@value TaxClearanceRenewalRule#RENEW_DAYS} days or less, gets an issue
 * attempt on e-Fisco. When e-Fisco refuses the access itself (captcha, Cloudflare) the run stops asking - the other
 * companies get the attempt recorded with the same reason, so the integrations' health shows it, and the pendencies
 * ("vencendo", "vencida") ask a person to issue it. Off with {@code efisco.tax-clearance.enabled=false} (or the old {@code efisco.certidao.enabled=false}).
 * Runs on a virtual thread; a run still going when the next fires is not doubled.
 */
@Component
public class TaxClearanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaxClearanceScheduler.class);
    /** Between two companies - e-Fisco is a public service, asked one company at a time. */
    static final long PAUSE_MS = 3000;
    /** On/off switch; the old key {@code efisco.certidao.enabled} still works as a fallback. */
    public static final String ENABLED_PROPERTY = "${efisco.tax-clearance.enabled:${efisco.certidao.enabled:true}}";
    /** Schedule; the old key {@code efisco.certidao.cron} still works as a fallback. */
    public static final String CRON_PROPERTY = "${efisco.tax-clearance.cron:${efisco.certidao.cron:0 0 6 * * MON}}";

    private final TaxClearanceService service;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final FirmAccessService firmAccessService;

    public TaxClearanceScheduler(TaxClearanceService service, @Value(ENABLED_PROPERTY) boolean enabled,
                             FirmAccessService firmAccessService) {
        this.firmAccessService = firmAccessService;
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(cron = CRON_PROPERTY)
    public void tick() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Certidão SEFAZ-PE run still going from the previous tick - skipped");
            return;
        }
        Thread.ofVirtual().name("tax-clearance-pe").start(() -> {
            try {
                runAll();
            } catch (RuntimeException e) {
                log.warn("Certidão SEFAZ-PE run failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    void runAll() {
        // F1-RN-10 (decision 15): no certidão attempt for a blocked firm
        RoutineGate gate = firmAccessService.routineGate("certidao");
        List<Company> due = service.dueCompanies().stream().filter(gate::allows).toList();
        gate.logSkipped();
        int issued = 0;
        String stoppedBy = null;
        for (Company company : due) {
            if (stoppedBy != null) {
                service.recordSkipped(company, stoppedBy);
                continue;
            }
            TaxClearanceService.Attempt attempt = service.issue(company);
            if (attempt.issued()) {
                issued++;
            } else if (attempt.stopsRun()) {
                stoppedBy = attempt.detail();
            }
            sleep();
        }
        log.info("Certidão SEFAZ-PE run: {} due, {} issued{}", due.size(), issued, stoppedBy == null ? "" : " - stopped: " + stoppedBy);
    }

    private void sleep() {
        try {
            Thread.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
