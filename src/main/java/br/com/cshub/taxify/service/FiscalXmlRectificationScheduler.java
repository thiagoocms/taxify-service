package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlRectificationProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class FiscalXmlRectificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlRectificationScheduler.class);

    private final FiscalXmlSyncControlRepository syncControlRepository;
    private final FiscalXmlDocumentRepository documentRepository;
    private final ProductFiscalRuleRepository ruleRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final FiscalXmlRectificationService rectificationService;
    private final FiscalXmlRectificationProperties properties;

    public FiscalXmlRectificationScheduler(FiscalXmlSyncControlRepository syncControlRepository, FiscalXmlDocumentRepository documentRepository,
                                            ProductFiscalRuleRepository ruleRepository, SubscriptionRepository subscriptionRepository,
                                            FiscalXmlRectificationService rectificationService, FiscalXmlRectificationProperties properties) {
        this.syncControlRepository = syncControlRepository;
        this.subscriptionRepository = subscriptionRepository;
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

        List<Company> companies = candidates();
        log.info("Starting scheduled fiscal XML rectification for {} companies", companies.size());

        for (Company company : companies) {
            try {
                rectifyCompany(company);
            } catch (Exception e) {
                log.warn("Scheduled fiscal XML rectification failed for company {}", company.getId(), e);
            }
        }
    }

    /**
     * Companies with rules (nothing to rectify without one) entitled today to the fiscal XML
     * import, whatever the source - SIEG, SEFAZ or both. One still in the middle of a SIEG import
     * waits for it to finish (its notes are still arriving); a company with no SIEG control
     * (SEFAZ only) or one never checked against SIEG is not held back.
     */
    List<Company> candidates() {
        Map<UUID, FiscalXmlSyncControl> siegControls = syncControlRepository.findAllWithCompany().stream()
                .collect(Collectors.toMap(control -> control.getCompany().getId(), control -> control, (first, second) -> first));
        LocalDate today = LocalDate.now();
        return ruleRepository.findCompaniesWithRules().stream()
                .filter(company -> !midSiegImport(siegControls.get(company.getId())))
                .filter(company -> entitled(company, today))
                .toList();
    }

    private static boolean midSiegImport(FiscalXmlSyncControl control) {
        return control != null && control.getTotalSieg() > 0 && control.getTotalImportado() < control.getTotalSieg();
    }

    private boolean entitled(Company company, LocalDate today) {
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        company.getId(), today, today)
                .map(Subscription::getPlan)
                .map(plan -> plan.getServices() != null && plan.getServices().contains(PlanServiceEnum.FISCAL_XML_IMPORT))
                .orElse(false);
    }

    private void rectifyCompany(Company company) {
        LocalDateTime from = LocalDateTime.now().minusDays(properties.getLookbackDays()).toLocalDate().atStartOfDay();
        LocalDateTime to = LocalDateTime.now().toLocalDate().atTime(LocalTime.MAX);

        List<FiscalXmlDocument> documents = documentRepository
                .findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(company.getId(), from, to);

        LocalDateTime rulesUpdatedAt = ruleRepository.findMaxLastModifiedDateByCompanyId(company.getId()).orElse(null);
        List<FiscalXmlDocument> needsCheck = documents.stream()
                .filter(document -> !document.isOwnIssue() && !document.isCanceled())
                // Only NFes can be rectified - a CT-e would fail on every run.
                .filter(document -> document.getDocumentType() == null || "NFE".equals(document.getDocumentType()))
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
