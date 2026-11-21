package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily incremental billing sync for every firm that chose an integrated provider (the
 * APIs have no webhooks, so polling it is). Each firm becomes a background job - the job
 * pool runs a few firms at a time, and a firm whose sync is already running is skipped.
 */
@Component
public class BillingSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingSyncScheduler.class);

    private final AccountingFirmRepository accountingFirmRepository;
    private final BillingSyncService syncService;
    private final boolean enabled;

    public BillingSyncScheduler(AccountingFirmRepository accountingFirmRepository, BillingSyncService syncService,
                                @Value("${billing.sync-enabled:true}") boolean enabled) {
        this.accountingFirmRepository = accountingFirmRepository;
        this.syncService = syncService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${billing.sync-cron:0 30 3 * * *}")
    public void syncAllFirms() {
        if (!enabled) {
            return;
        }
        for (AccountingFirm firm : accountingFirmRepository.findAll()) {
            if (firm.isDeleted() || firm.getBillingProvider() == BillingProviderType.MANUAL) {
                continue;
            }
            try {
                syncService.start(firm, true);
            } catch (Exception e) {
                log.warn("Scheduled billing sync not started for firm {}: {}", firm.getId(), e.getMessage());
            }
        }
    }
}
