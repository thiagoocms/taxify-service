package br.com.cshub.taxify.service;

import br.com.cshub.taxify.integration.fiscalxml.ProviderCallContext;
import br.com.cshub.taxify.integration.fiscalxml.ProviderCallPurpose;
import br.com.cshub.taxify.service.FiscalXmlIntegrationService.ReconciledPage;
import br.com.cshub.taxify.service.FiscalXmlIntegrationService.ReconciliationNeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * B1: the re-read from the first page a side asked for ({@link ReconciliationNeed}), outside the side's transaction: each
 * page is stored in its own ({@link FiscalXmlIntegrationService#reconcilePage}, through the proxy), so the rate limiter's
 * waits never hold a connection, and a page that fails stops the pass keeping what the earlier ones brought. Only a pass
 * that reaches its end is recorded ({@link FiscalXmlIntegrationService#recordReconciliation}) - an interrupted one is
 * asked for again on the next attempt. Never throws: the side's own result already stands.
 */
@Service
public class SiegReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(SiegReconciliationService.class);

    private final FiscalXmlIntegrationService integrationService;

    public SiegReconciliationService(FiscalXmlIntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    /** @return new notes the pass stored (0 when nothing was needed) */
    public int reconcile(ReconciliationNeed need) {
        if (need == null) {
            return 0;
        }
        return ProviderCallContext.call(ProviderCallPurpose.RECONCILE, () -> run(need));
    }

    private int run(ReconciliationNeed need) {
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
        } catch (RuntimeException e) {
            log.warn("SIEG reconciliation of company {} ({}) not recorded - asked again next time: {}", need.companyId(), need.side(),
                    e.getClass().getSimpleName());
        }
        log.info("SIEG reconciliation of company {} ({}): {} of {} notes delivered, {} new", need.companyId(), need.side(),
                delivered, need.totalSieg(), persistedNew);
        return persistedNew;
    }
}
