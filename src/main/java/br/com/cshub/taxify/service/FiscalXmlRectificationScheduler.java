package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlRectificationProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Component
public class FiscalXmlRectificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlRectificationScheduler.class);

    private final FiscalXmlSyncControlRepository syncControlRepository;
    private final FiscalXmlDocumentRepository documentRepository;
    private final ProductFiscalRuleRepository ruleRepository;
    private final FiscalXmlRectificationService rectificationService;
    private final FiscalXmlRectificationProperties properties;

    public FiscalXmlRectificationScheduler(FiscalXmlSyncControlRepository syncControlRepository, FiscalXmlDocumentRepository documentRepository,
                                            ProductFiscalRuleRepository ruleRepository, FiscalXmlRectificationService rectificationService,
                                            FiscalXmlRectificationProperties properties) {
        this.syncControlRepository = syncControlRepository;
        this.documentRepository = documentRepository;
        this.ruleRepository = ruleRepository;
        this.rectificationService = rectificationService;
        this.properties = properties;
    }

    @Scheduled(cron = "${fiscal-xml.rectification.cron:0 3 * * * *}")
    public void rectifyAllCompanies() {
        if (!properties.isEnabled()) {
            return;
        }

        List<FiscalXmlSyncControl> fullySynced = syncControlRepository.findAllWithCompany().stream()
                .filter(this::isFullySynced)
                .toList();
        log.info("Starting scheduled fiscal XML rectification for {} fully-synced companies", fullySynced.size());

        for (FiscalXmlSyncControl control : fullySynced) {
            Company company = control.getCompany();
            try {
                rectifyCompany(company);
            } catch (Exception e) {
                log.warn("Scheduled fiscal XML rectification failed for company {}", company.getId(), e);
            }
        }
    }

    private boolean isFullySynced(FiscalXmlSyncControl control) {
        return control.getTotalSieg() > 0 && control.getTotalImportado() == control.getTotalSieg();
    }

    private void rectifyCompany(Company company) {
        LocalDateTime from = LocalDateTime.now().minusDays(properties.getLookbackDays()).toLocalDate().atStartOfDay();
        LocalDateTime to = LocalDateTime.now().toLocalDate().atTime(LocalTime.MAX);

        List<FiscalXmlDocument> documents = documentRepository
                .findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(company.getId(), from, to);

        LocalDateTime rulesUpdatedAt = ruleRepository.findMaxLastModifiedDateByCompanyId(company.getId()).orElse(null);
        List<FiscalXmlDocument> needsCheck = documents.stream()
                .filter(document -> needsRectificationCheck(document, rulesUpdatedAt))
                .toList();

        int rectified = 0;
        int failed = 0;
        for (FiscalXmlDocument document : needsCheck) {
            try {
                FiscalXmlRectificationResultDTO result = rectificationService.rectify(document.getId());
                if (!result.correcoesAplicadas().isEmpty()) {
                    rectified++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Scheduled fiscal XML rectification failed for document {}", document.getId(), e);
            }
        }

        log.info("Fiscal XML rectification for company {}: {} of {} notes needed a check ({} already up to date), {} rectified, {} failed",
                company.getId(), needsCheck.size(), documents.size(), documents.size() - needsCheck.size(), rectified, failed);
    }

    private boolean needsRectificationCheck(FiscalXmlDocument document, LocalDateTime rulesUpdatedAt) {
        LocalDateTime lastChecked = document.getLastRectificationCheckedAt();
        return lastChecked == null || (rulesUpdatedAt != null && rulesUpdatedAt.isAfter(lastChecked));
    }
}
