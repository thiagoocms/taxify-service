package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.integration.acessorias.AcessoriasProperties;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily incremental Acessórias import for every firm that configured a token: only
 * companies changed since that firm's last import are fetched (see
 * {@link AcessoriasImportService#importFirm}). One firm failing (bad token, API down)
 * doesn't stop the others.
 */
@Component
public class AcessoriasImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(AcessoriasImportScheduler.class);

    private final AccountingFirmRepository accountingFirmRepository;
    private final AcessoriasImportService importService;
    private final AcessoriasProperties properties;

    public AcessoriasImportScheduler(AccountingFirmRepository accountingFirmRepository, AcessoriasImportService importService,
                                     AcessoriasProperties properties) {
        this.accountingFirmRepository = accountingFirmRepository;
        this.importService = importService;
        this.properties = properties;
    }

    @Scheduled(cron = "${acessorias.sync-cron:0 0 3 * * *}")
    public void importAllFirms() {
        if (!properties.isSyncEnabled()) {
            return;
        }

        for (AccountingFirm firm : accountingFirmRepository.findByAcessoriasApiTokenIsNotNullAndDeletedIsFalse()) {
            if (firm.getAcessoriasApiToken().isBlank()) {
                continue;
            }
            try {
                importService.importFirm(firm, true);
            } catch (Exception e) {
                log.warn("Scheduled Acessórias import failed for firm {}", firm.getId(), e);
            }
        }
    }
}
