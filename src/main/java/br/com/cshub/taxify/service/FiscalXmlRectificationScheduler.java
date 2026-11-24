package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlRectificationProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Component
public class FiscalXmlRectificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlRectificationScheduler.class);

    private final FiscalXmlDocumentRepository documentRepository;
    private final ProductFiscalRuleRepository ruleRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final FiscalXmlRectificationService rectificationService;
    private final FiscalXmlRectificationProperties properties;

    public FiscalXmlRectificationScheduler(FiscalXmlDocumentRepository documentRepository,
                                            ProductFiscalRuleRepository ruleRepository, SubscriptionRepository subscriptionRepository,
                                            FiscalXmlRectificationService rectificationService, FiscalXmlRectificationProperties properties) {
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
     * import, whatever the source - SIEG, SEFAZ or both. A company whose SIEG import is incomplete is not held
     * back: documents SIEG counts that never become an NF-e here (a CT-e, a parse error) would hold it forever.
     */
    List<Company> candidates() {
        LocalDate today = LocalDate.now();
        return ruleRepository.findCompaniesWithRules().stream()
                .filter(company -> entitled(company, today))
                .toList();
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
