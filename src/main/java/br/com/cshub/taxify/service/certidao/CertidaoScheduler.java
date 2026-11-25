package br.com.cshub.taxify.service.certidao;

import br.com.cshub.taxify.domain.company.Company;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The certidão SEFAZ-PE on its own: once a week ({@code efisco.certidao.cron}, Monday 6h by default) every PE company
 * without a certidão, or whose certidão expires in {@value CertidaoRenewalRule#RENEW_DAYS} days or less, gets an issue
 * attempt on e-Fisco. When e-Fisco refuses the access itself (captcha, Cloudflare) the run stops asking - the other
 * companies get the attempt recorded with the same reason, so the integrations' health shows it, and the pendencies
 * ("vencendo", "vencida") ask a person to issue it. Off with {@code efisco.certidao.enabled=false}.
 * Runs on a virtual thread; a run still going when the next fires is not doubled.
 */
@Component
public class CertidaoScheduler {

    private static final Logger log = LoggerFactory.getLogger(CertidaoScheduler.class);
    /** Between two companies - e-Fisco is a public service, asked one company at a time. */
    static final long PAUSE_MS = 3000;

    private final CertidaoService service;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CertidaoScheduler(CertidaoService service, @Value("${efisco.certidao.enabled:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${efisco.certidao.cron:0 0 6 * * MON}")
    public void tick() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Certidão SEFAZ-PE run still going from the previous tick - skipped");
            return;
        }
        Thread.ofVirtual().name("certidao-pe").start(() -> {
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
        List<Company> due = service.dueCompanies();
        int issued = 0;
        String stoppedBy = null;
        for (Company company : due) {
            if (stoppedBy != null) {
                service.recordSkipped(company, stoppedBy);
                continue;
            }
            CertidaoService.Attempt attempt = service.issue(company);
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
