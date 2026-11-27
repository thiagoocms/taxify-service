package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.certificate.FirmCertificate;
import br.com.cshub.taxify.repository.FirmCertificateRepository;
import br.com.cshub.taxify.service.firmaccess.BlockedPeriodRecoveryService;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.RoutineGate;
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
    private final FirmAccessService firmAccessService;
    /** Recovery of the blocked months (QA-02); null = no recovery (tests built without it). */
    private final BlockedPeriodRecoveryService recoveryService;

    public EfiscoSyncScheduler(FirmCertificateRepository certificateRepository, EfiscoSyncService syncService,
                               boolean enabled, FirmAccessService firmAccessService) {
        this(certificateRepository, syncService, enabled, firmAccessService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EfiscoSyncScheduler(FirmCertificateRepository certificateRepository, EfiscoSyncService syncService,
                               @Value("${efisco.sync.enabled:true}") boolean enabled, FirmAccessService firmAccessService,
                               BlockedPeriodRecoveryService recoveryService) {
        this.firmAccessService = firmAccessService;
        this.recoveryService = recoveryService;
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
        // F1-RN-10: no e-Fisco capture for a blocked firm
        RoutineGate gate = firmAccessService.routineGate("efisco");
        java.util.Set<java.util.UUID> served = new java.util.HashSet<>();
        for (FirmCertificate certificate : certificateRepository.findAllActive()) {
            if (certificate.getValidUntil().isBefore(LocalDateTime.now())
                    || !gate.allowsFirm(certificate.getAccountingFirm().getId())) {
                continue;
            }
            EfiscoSyncService.SyncResult result = syncService.syncFirm(certificate.getAccountingFirm().getId(), periods);
            log.info("e-Fisco sync of firm {}: {} - {}", certificate.getAccountingFirm().getId(), result.status(), result.detail());
            java.util.UUID firmId = certificate.getAccountingFirm().getId();
            served.add(firmId);
            if (recoveryService != null) {
                // QA-02: the blocked months before the previous one, once, after the reactivation
                try {
                    recoveryService.runEfisco(firmId, months -> syncService.syncFirm(firmId, months).status());
                } catch (RuntimeException e) {
                    log.warn("Blocked-months e-Fisco recovery failed for firm {}", firmId, e);
                }
            }
        }
        gate.logSkipped();
        if (recoveryService != null) {
            recoveryService.skipEfiscoOrphans(served, gate);
        }
    }
}
