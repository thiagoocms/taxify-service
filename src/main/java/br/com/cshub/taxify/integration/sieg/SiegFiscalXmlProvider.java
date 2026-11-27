package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlProvider;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncRequest;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncResult;
import br.com.cshub.taxify.service.FiscalXmlIntegrationService;
import br.com.cshub.taxify.service.ServiceInvoiceService;
import br.com.cshub.taxify.service.SiegReconciliationService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapts SIEG to the {@link FiscalXmlProvider} strategy contract. Registered under the
 * bean name "sieg" - {@code fiscal-xml.sync.provider} selects it by that name.
 */
@Component("sieg")
public class SiegFiscalXmlProvider implements FiscalXmlProvider {

    private static final int TIPO_XML_NFE = 1;

    private final FiscalXmlIntegrationService integrationService;
    private final SiegProperties properties;
    private final ServiceInvoiceService serviceInvoiceService;
    private final SiegReconciliationService reconciliationService;

    public SiegFiscalXmlProvider(FiscalXmlIntegrationService integrationService, SiegProperties properties,
                                 ServiceInvoiceService serviceInvoiceService, SiegReconciliationService reconciliationService) {
        this.serviceInvoiceService = serviceInvoiceService;
        this.reconciliationService = reconciliationService;
        this.integrationService = integrationService;
        this.properties = properties;
    }

    /**
     * One side: SIEG's skip is what SIEG itself already delivered of the window, read by
     * {@link FiscalXmlIntegrationService#syncSideFromSieg} (B1) - {@code alreadyImportedCount} only matters for a sync
     * without a side. A reconciliation the side asked for (B1) runs after the side's transaction, page by page.
     */
    @Override
    public FiscalXmlSyncResult sync(FiscalXmlSyncRequest request) {
        XmlDownloadFilterDTO filter = new XmlDownloadFilterDTO(
                request.companyId().toString(),
                TIPO_XML_NFE,
                request.dataEmissaoInicio(),
                request.dataEmissaoFim(),
                50,
                request.alreadyImportedCount(),
                null,
                request.side()
        );

        FiscalXmlIntegrationService.SiegSideOutcome outcome = request.side() == null
                ? new FiscalXmlIntegrationService.SiegSideOutcome(integrationService.syncFromSieg(filter, request.knownPending()), false)
                : integrationService.syncSideFromSieg(filter, request.knownPending());
        XmlDownloadResultDTO result = outcome.result();
        int reconciled = reconciliationService.reconcile(outcome.reconciliation());
        return new FiscalXmlSyncResult(result.totalPersistedNew() + reconciled, result.totalDuplicatedSkipped(),
                result.totalParseErrors(), outcome.countFailed(), outcome.serviceInvoiceCount());
    }

    @Override
    public FiscalXmlSyncResult checkPreviousMonth(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        FiscalXmlIntegrationService.SiegSideOutcome outcome = integrationService.checkPreviousMonthFromSieg(companyId, side, from, to);
        int reconciled = reconciliationService.reconcile(outcome.reconciliation());
        XmlDownloadResultDTO result = outcome.result();
        return new FiscalXmlSyncResult(result.totalPersistedNew() + reconciled, result.totalDuplicatedSkipped(),
                result.totalParseErrors(), outcome.countFailed());
    }

    @Override
    public int refreshEvents(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        return integrationService.refreshRecentEvents(companyId, side, from, to);
    }

    @Override
    public Optional<RecoveryPage> recoverPage(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to, int page) {
        XmlDownloadResultDTO result = integrationService.syncPeriodPageFromSieg(companyId, side, from, to, page);
        return Optional.of(new RecoveryPage(result.totalXmlsCounted(), result.totalPersistedNew(),
                result.totalXmlsCounted() < FiscalXmlIntegrationService.pageSize()));
    }

    @Override
    public int syncServiceInvoices(UUID companyId, LocalDate from, LocalDate to) {
        return serviceInvoiceService.syncFromSieg(companyId, from, to).persistedNew();
    }

    @Override
    public int syncServiceInvoicesIfMissing(UUID companyId, LocalDate from, LocalDate to, Integer issuerCount,
                                            Integer recipientCount) {
        return serviceInvoiceService.syncMonthIfMissing(companyId, from, to, issuerCount, recipientCount).persistedNew();
    }

    @Override
    public int syncServiceInvoicesShadow(UUID companyId, LocalDate from, LocalDate to, Integer issuerCount,
                                         Integer recipientCount) {
        return serviceInvoiceService.syncWithShadowGate(companyId, from, to, issuerCount, recipientCount).persistedNew();
    }
}
