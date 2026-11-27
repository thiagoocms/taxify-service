package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.domain.firmaccess.FirmSubscriptionSnapshot;

import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * F1-RN-07: uma vez por dia (Recife) grava a passagem de TRIAL vencido e de ACTIVE com {@code access_until} vencido
 * para BLOCKED, com {@code blocked_since}, o motivo e o evento BLOCKED. O acesso já é calculado na hora por
 * {@link FirmAccessRules}; aqui é só o registro. O escritório isento nunca é bloqueado (F1-RN-12).
 */
@Component
public class FirmAccessScheduler {

    private static final Logger log = LoggerFactory.getLogger(FirmAccessScheduler.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final FirmSubscriptionRepository repository;
    private final FirmAccessService accessService;
    private final FirmEventService eventService;
    private final TransactionTemplate transaction;
    private final AccountingFirmRepository firmRepository;
    private final SaasProperties properties;

    public FirmAccessScheduler(FirmSubscriptionRepository repository, FirmAccessService accessService,
                               FirmEventService eventService, PlatformTransactionManager transactionManager,
                               AccountingFirmRepository firmRepository, SaasProperties properties) {
        this.firmRepository = firmRepository;
        this.properties = properties;
        this.repository = repository;
        this.accessService = accessService;
        this.eventService = eventService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${taxify.saas.access-cron:0 10 0 * * *}", zone = "America/Recife")
    public void tick() {
        if (!properties.isAccessEnabled()) {
            return;
        }
        // one run at a time: a slow run is not overlapped by the next tick (convention 3.8.8)
        if (!running.compareAndSet(false, true)) {
            log.info("Firm access expiry still running from the previous tick - skipped");
            return;
        }
        try {
            int blocked = recordExpired();
            if (blocked > 0) {
                log.info("firm.access.expired recorded={}", blocked);
            }
        } catch (RuntimeException e) {
            log.warn("Firm access expiry run failed", e);
        } finally {
            running.set(false);
        }
    }

    public int recordExpired() {
        List<FirmSubscription> candidates = repository.findByStatusInAndDeletedIsFalse(
                List.of(FirmSubscriptionStatus.TRIAL, FirmSubscriptionStatus.ACTIVE));
        int recorded = 0;
        for (FirmSubscription candidate : candidates) {
            FirmAccess access = accessService.evaluate(candidate.getAccountingFirmId(), candidate);
            if (access.exempt() || access.status() != FirmSubscriptionStatus.BLOCKED) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(transaction.execute(status -> recordBlocked(candidate.getAccountingFirmId())))) {
                    recorded++;
                }
            } catch (RuntimeException e) {
                log.warn("Firm access expiry not recorded for firm {}", candidate.getAccountingFirmId(), e);
            }
        }
        return recorded;
    }

    /**
     * Inside the transaction: the same lock as the platform admin's actions (PlatformFirmService#apply), then the row
     * read again - an extension or release made meanwhile wins, and nothing is written.
     */
    private boolean recordBlocked(UUID firmId) {
        firmRepository.lock(firmId);
        FirmSubscription subscription = repository.findForUpdate(firmId).orElse(null);
        if (subscription == null || (subscription.getStatus() != FirmSubscriptionStatus.TRIAL
                && subscription.getStatus() != FirmSubscriptionStatus.ACTIVE)) {
            return false;
        }
        FirmAccess access = accessService.evaluate(firmId, subscription);
        if (access.exempt() || access.status() != FirmSubscriptionStatus.BLOCKED) {
            return false;
        }
        FirmSubscriptionSnapshot before = subscription.snapshot();
        subscription.setStatus(FirmSubscriptionStatus.BLOCKED);
        subscription.setBlockedSince(access.blockedSince());
        subscription.setBlockedReason(access.reason());
        repository.save(subscription);
        eventService.record(firmId, FirmEventType.BLOCKED, before, subscription.snapshot(), access.reason().name(),
                FirmEventService.SYSTEM);
        return true;
    }
}
