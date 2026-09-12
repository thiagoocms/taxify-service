package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlRectificationProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Automatic counterpart to {@link FiscalXmlSyncScheduler}: on its own cron, re-checks
 * every recently synced {@link FiscalXmlDocument} against the {@link br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule}s
 * registered for its company, via {@link FiscalXmlRectificationService#rectify}. A
 * document with nothing to fix is a cheap no-op (no file written, no audit log) - so
 * running this repeatedly over the same lookback window is safe, and it also picks up
 * notes that only started diverging because a rule was added/changed after the note
 * was downloaded.
 *
 * <p>Only rectifies companies whose {@link FiscalXmlSyncControl} shows
 * {@code totalImportado == totalSieg} - fully caught up with SIEG for the sync's current
 * window. A company still mid-import might have more notes still to arrive, so
 * rectifying now could miss some (or, if a rule changes between syncs, silently skip
 * documents that would otherwise be re-checked once the sync scheduler picks them up
 * anyway on a later run). {@code totalSieg == 0} never counts as "caught up" here - same
 * as in {@link FiscalXmlSyncScheduler}, that's "never successfully checked" as much as
 * "no pending notes".
 */
@Component
public class FiscalXmlRectificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlRectificationScheduler.class);

    private final FiscalXmlSyncControlRepository syncControlRepository;
    private final FiscalXmlDocumentRepository documentRepository;
    private final FiscalXmlRectificationService rectificationService;
    private final FiscalXmlRectificationProperties properties;

    public FiscalXmlRectificationScheduler(FiscalXmlSyncControlRepository syncControlRepository, FiscalXmlDocumentRepository documentRepository,
                                            FiscalXmlRectificationService rectificationService,
                                            FiscalXmlRectificationProperties properties) {
        this.syncControlRepository = syncControlRepository;
        this.documentRepository = documentRepository;
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

        int rectified = 0;
        int failed = 0;
        for (FiscalXmlDocument document : documents) {
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

        log.info("Fiscal XML rectification for company {}: {} of {} notes rectified, {} failed",
                company.getId(), rectified, documents.size(), failed);
    }
}
