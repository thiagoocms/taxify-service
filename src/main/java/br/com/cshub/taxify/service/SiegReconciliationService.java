package br.com.cshub.taxify.service;

import br.com.cshub.taxify.integration.fiscalxml.ProviderCallContext;
import br.com.cshub.taxify.integration.fiscalxml.ProviderCallPurpose;
import br.com.cshub.taxify.service.FiscalXmlIntegrationService.ReconciledPage;
import br.com.cshub.taxify.service.FiscalXmlIntegrationService.ReconciliationNeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B1: the re-read from the first page a side asked for ({@link ReconciliationNeed}), outside the side's transaction: each
 * page is stored in its own ({@link FiscalXmlIntegrationService#reconcilePage}, through the proxy), so the rate limiter's
 * waits never hold a connection, and a page that fails stops the pass keeping what the earlier ones brought. Only a pass
 * that reaches its end is recorded ({@link FiscalXmlIntegrationService#recordReconciliation}) - an interrupted one is
 * asked for again - but a pass interrupted today is not retried before tomorrow (per company, side and month, in memory:
 * a restart or a second instance can try once more), so a SIEG error stuck on one page does not re-read the earlier pages
 * on every turn of the company. Never throws: the side's own result already stands.
 */
@Service
public class SiegReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(SiegReconciliationService.class);

    private final FiscalXmlIntegrationService integrationService;
    /** America/Recife (ClockConfig): the day of an interrupted pass. */
    private final Clock clock;
    /** company:side:yyyy-MM of the passes interrupted by a failure, with the day it happened. */
    private final Map<String, LocalDate> interrupted = new ConcurrentHashMap<>();

    public SiegReconciliationService(FiscalXmlIntegrationService integrationService, Clock clock) {
        this.integrationService = integrationService;
        this.clock = clock;
    }

    /** @return new notes the pass stored (0 when nothing was needed) */
    public int reconcile(ReconciliationNeed need) {
        if (need == null) {
            return 0;
        }
        String key = need.companyId() + ":" + need.side() + ":" + YearMonth.from(need.from());
        LocalDate today = LocalDate.now(clock);
        if (today.equals(interrupted.get(key))) {
            log.info("SIEG reconciliation of company {} ({}) interrupted today - tried again tomorrow", need.companyId(), need.side());
            return 0;
        }
        return ProviderCallContext.call(ProviderCallPurpose.RECONCILE, () -> run(need, key, today));
    }

    private int run(ReconciliationNeed need, String key, LocalDate today) {
        log.info("SIEG paging of company {} ({}) ended with {} of {} notes delivered - reconciling from the first page (at most {} pages)",
                need.companyId(), need.side(), need.delivered(), need.totalSieg(), need.pages());
        int delivered = need.delivered();
        int persistedNew = 0;
        for (int index = 0; index < need.pages() && delivered < need.totalSieg(); index++) {
            ReconciledPage page;
            try {
                page = integrationService.reconcilePage(need, index);
            } catch (RuntimeException e) {
                log.warn("SIEG reconciliation of company {} ({}) stopped at page {} - kept what came, asked again next time: {}",
                        need.companyId(), need.side(), index, e.getClass().getSimpleName());
                interrupted.put(key, today);
                return persistedNew;
            }
            persistedNew += page.persistedNew();
            delivered = page.delivered();
            if (page.items() < FiscalXmlIntegrationService.pageSize()) {
                break;
            }
        }
        try {
            integrationService.recordReconciliation(need);
            interrupted.remove(key);
        } catch (RuntimeException e) {
            interrupted.put(key, today);
            log.warn("SIEG reconciliation of company {} ({}) not recorded - asked again next time: {}", need.companyId(), need.side(),
                    e.getClass().getSimpleName());
        }
        log.info("SIEG reconciliation of company {} ({}): {} of {} notes delivered, {} new", need.companyId(), need.side(),
                delivered, need.totalSieg(), persistedNew);
        return persistedNew;
    }
}
