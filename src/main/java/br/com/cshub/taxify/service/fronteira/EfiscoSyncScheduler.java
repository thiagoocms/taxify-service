package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.certificate.FirmCertificate;
import br.com.cshub.taxify.repository.FirmCertificateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The e-Fisco sync on its own: once a day ({@code efisco.sync.cron}, 7h by default), every firm with the
 * accountant's certificate gets the extract of the current month of each PE company, and of the previous one while
 * it is still open (until its due date, or paid) - the "Sincronizar com e-Fisco" button is there for anything newer.
 * An extract equal to the one imported is kept as it is. Off with {@code efisco.sync.enabled=false}.
 * Runs on a virtual thread; a run still going when the next fires is not doubled.
 */
@Component
public class EfiscoSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EfiscoSyncScheduler.class);

    private final FirmCertificateRepository certificateRepository;
    private final EfiscoSyncService syncService;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EfiscoSyncScheduler(FirmCertificateRepository certificateRepository, EfiscoSyncService syncService,
                               @Value("${efisco.sync.enabled:true}") boolean enabled) {
        this.certificateRepository = certificateRepository;
        this.syncService = syncService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${efisco.sync.cron:0 0 7 * * *}")
    public void tick() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("e-Fisco sync still running from the previous tick - skipped");
            return;
        }
        Thread.ofVirtual().name("efisco-sync").start(() -> {
            try {
                syncAll();
            } catch (RuntimeException e) {
                log.warn("e-Fisco sync run failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    void syncAll() {
        YearMonth current = YearMonth.now();
        List<YearMonth> periods = List.of(current.minusMonths(1), current);
        for (FirmCertificate certificate : certificateRepository.findAllActive()) {
            if (certificate.getValidUntil().isBefore(LocalDateTime.now())) {
                continue;
            }
            EfiscoSyncService.SyncResult result = syncService.syncFirm(certificate.getAccountingFirm().getId(), periods);
            log.info("e-Fisco sync of firm {}: {} - {}", certificate.getAccountingFirm().getId(), result.status(), result.detail());
        }
    }
}
