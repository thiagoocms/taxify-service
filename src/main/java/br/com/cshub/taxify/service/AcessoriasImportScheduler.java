package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.integration.acessorias.AcessoriasProperties;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.enumerated.IntegrationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily incremental Acessórias import for every firm that configured a token: only
 * companies changed since that firm's last import are fetched (see
 * {@link AcessoriasImportService#importFirm}). Each firm runs as a background job (a few at
 * a time, never two for the same firm); one failing (bad token, API down) doesn't stop the others.
 */
@Component
public class AcessoriasImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(AcessoriasImportScheduler.class);

    private final FirmIntegrationService firmIntegrationService;
    private final AcessoriasImportService importService;
    private final AcessoriasProperties properties;

    public AcessoriasImportScheduler(FirmIntegrationService firmIntegrationService, AcessoriasImportService importService,
                                     AcessoriasProperties properties) {
        this.firmIntegrationService = firmIntegrationService;
        this.importService = importService;
        this.properties = properties;
    }

    @Scheduled(cron = "${acessorias.sync-cron:0 0 3 * * *}")
    public void importAllFirms() {
        if (!properties.isSyncEnabled()) {
            return;
        }

        for (FirmIntegration integration : firmIntegrationService.findConfigured(IntegrationType.ACESSORIAS)) {
            AccountingFirm firm = integration.getAccountingFirm();
            try {
                importService.start(firm, true);
            } catch (Exception e) {
                log.warn("Scheduled Acessórias import not started for firm {}: {}", firm.getId(), e.getMessage());
            }
        }
    }
}
