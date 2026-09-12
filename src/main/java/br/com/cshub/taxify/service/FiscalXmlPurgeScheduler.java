package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPurgeProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Deletes the physical XML files (original and, if present, the latest rectified copy)
 * of notes older than {@link FiscalXmlPurgeProperties#getRetentionMonths()}, based on
 * {@link FiscalXmlDocument#getIssueDate()} - the note's own fiscal date, not when it was
 * imported, so a note synced late is purged on the same schedule as one synced promptly.
 *
 * <p>Only the files on disk are removed. {@link FiscalXmlDocument} itself, and every
 * extracted field on it, stays in the database untouched - reporting and the sync
 * control table keep working for purged notes; only re-reading or exporting the raw XML
 * stops being possible once its file is gone (the existing "file missing" handling in
 * {@link FiscalXmlIntegrationService#searchStoredXmls} and {@code #exportXmls}/{@code
 * #exportXmlsZip} already skips-and-logs rather than failing, so this doesn't need any
 * special-casing there). Deleting an already-purged file is a safe no-op, so this can run
 * repeatedly without needing to track what it already purged.
 */
@Component
public class FiscalXmlPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlPurgeScheduler.class);

    private final FiscalXmlDocumentRepository documentRepository;
    private final FiscalXmlPurgeProperties properties;

    public FiscalXmlPurgeScheduler(FiscalXmlDocumentRepository documentRepository, FiscalXmlPurgeProperties properties) {
        this.documentRepository = documentRepository;
        this.properties = properties;
    }

    @Scheduled(cron = "${fiscal-xml.purge.cron:0 0 4 1 * *}")
    public void purgeOldFiles() {
        if (!properties.isEnabled()) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMonths(properties.getRetentionMonths());
        List<FiscalXmlDocument> candidates = documentRepository.findByIssueDateBeforeAndDeletedIsFalse(cutoff);

        int purged = 0;
        int failed = 0;
        for (FiscalXmlDocument document : candidates) {
            try {
                if (purgeFiles(document)) {
                    purged++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("Failed to purge files for fiscal XML document {}", document.getId(), e);
            }
        }

        log.info("Fiscal XML file purge (retention={} months, cutoff={}): {} of {} notes had a file removed, {} failed",
                properties.getRetentionMonths(), cutoff.toLocalDate(), purged, candidates.size(), failed);
    }

    private boolean purgeFiles(FiscalXmlDocument document) throws IOException {
        boolean deletedOriginal = deleteIfExists(document.getXmlFilePath());
        boolean deletedRectified = deleteIfExists(document.getRectifiedFilePath());
        return deletedOriginal || deletedRectified;
    }

    private boolean deleteIfExists(String path) throws IOException {
        if (path == null || path.isBlank()) {
            return false;
        }
        return Files.deleteIfExists(Path.of(path));
    }
}
