package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.enumerated.InvariantScope;
import br.com.cshub.taxify.exception.FiscalDocumentInvariantException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPurgeProperties;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.service.routine.RoutineRunReservation;
import br.com.cshub.taxify.service.routine.RoutineRunReservation.Reservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deletes the physical XML files (original and, if present, the latest rectified copy) of NF-e, NFC-e and CT-e older
 * than {@link FiscalXmlPurgeProperties#getRetentionMonths()}, based on the issue date - the note's own fiscal date, not
 * when it was imported (FD-RN-16).
 *
 * <p>Only the stored files (disk or S3) are removed; the document and every field on it stay. Each purged document
 * gets {@code xml_purged_at} (Recife), so a later run never lists it again (index idx_fd_purge). The candidates are read
 * in pages of {@value #PAGE_SIZE} by id (keyset - never every old note in memory), each page in a short transaction of
 * its own. A file that fails leaves only that document unmarked (the next run tries again) and never stops the page.
 * NFS-e stay out (P-FD-05). Two instances: one run per day ({@code routine_runs}), an {@link AtomicBoolean} against
 * overlap here; the mark is idempotent and deleting a file already gone is no error.
 */
@Component
public class FiscalXmlPurgeScheduler {

    static final String ROUTINE = "fiscal-xml-purge";
    static final int PAGE_SIZE = 500;
    private static final int STALE_MINUTES = 120;

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlPurgeScheduler.class);

    private final FiscalDocumentRepository documentRepository;
    private final FiscalXmlPurgeProperties properties;
    private final FiscalXmlFileStorage fileStorage;
    private final RoutineRunReservation reservation;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FiscalXmlPurgeScheduler(FiscalDocumentRepository documentRepository, FiscalXmlPurgeProperties properties,
                                   FiscalXmlFileStorage fileStorage, RoutineRunReservation reservation,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.fileStorage = fileStorage;
        this.documentRepository = documentRepository;
        this.properties = properties;
        this.reservation = reservation;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** The outcome of one run: documents marked, candidates read, files that failed. */
    record PurgeSummary(int purged, int candidates, int failed, int pages) {
    }

    @Scheduled(cron = "${fiscal-xml.purge.cron:0 0 4 1 * *}", zone = "America/Recife")
    public void purgeOldFiles() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            Optional<Reservation> reserved = reservation.reserve(ROUTINE, STALE_MINUTES);
            if (reserved.isEmpty()) {
                log.info("Fiscal XML file purge: another instance has today's run - skipped");
                return;
            }
            PurgeSummary summary = purge();
            reservation.finish(reserved.get(), summary.purged(), summary.failed());
        } finally {
            running.set(false);
        }
    }

    /** Every page of candidates of the cutoff of now. */
    PurgeSummary purge() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusMonths(properties.getRetentionMonths());
        int purged = 0;
        int candidates = 0;
        int failed = 0;
        int pages = 0;
        String afterId = "";
        while (true) {
            String from = afterId;
            String[] lastRead = {null};
            PageResult page;
            try {
                page = transaction.execute(status -> purgePage(cutoff, from, lastRead));
            } catch (RuntimeException e) {
                // the page's transaction could not commit (a save failed): its marks are undone, the files of the
                // documents before it may be gone (deleting again is no error) - the next run retries them; this run
                // goes on after the page
                if (lastRead[0] == null) {
                    log.warn("fiscal.xml.purge.page.failed afterId={} read=0 error={}", from, e.getClass().getSimpleName());
                    break;
                }
                log.warn("fiscal.xml.purge.page.failed afterId={} lastId={} error={}", from, lastRead[0], e.getClass().getSimpleName());
                pages++;
                failed++;
                afterId = lastRead[0];
                continue;
            }
            if (page == null || page.read() == 0) {
                break;
            }
            pages++;
            candidates += page.read();
            purged += page.purged();
            failed += page.failed();
            if (page.read() < PAGE_SIZE) {
                break;
            }
            afterId = page.lastId();
        }
        log.info("Fiscal XML file purge (retention={} months, cutoff={}): {} of {} notes purged (xml_purged_at), {} failed, {} pages",
                properties.getRetentionMonths(), cutoff.toLocalDate(), purged, candidates, failed, pages);
        return new PurgeSummary(purged, candidates, failed, pages);
    }

    private record PageResult(int read, int purged, int failed, String lastId) {
    }

    /**
     * One page: each document has its invariants checked (UPDATE) before it is marked and any file deleted - a document
     * the model refuses keeps its files and stays unmarked -, then the files go and the mark is flushed at once
     * ({@code saveAndFlush}), so a failing row shows up on its own document. {@code lastRead[0]}: the page's last id, known before anything can fail.
     */
    private PageResult purgePage(LocalDateTime cutoff, String afterId, String[] lastRead) {
        List<FiscalDocument> documents = documentRepository.findPurgeCandidates(cutoff, afterId, PageRequest.of(0, PAGE_SIZE));
        String lastId = documents.isEmpty() ? afterId : String.valueOf(documents.get(documents.size() - 1).getId());
        lastRead[0] = documents.isEmpty() ? null : lastId;
        int purged = 0;
        int failed = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (FiscalDocument document : documents) {
            if (document.isXmlPurged()) {
                continue;
            }
            try {
                // a document the model refuses is left as it is: files kept, not marked (the flush never meets it)
                document.verifyInvariants(InvariantScope.UPDATE);
                purgeFiles(document);
            } catch (FiscalDocumentInvariantException e) {
                failed++;
                log.warn("fiscal.xml.purge.document.refused documentId={} invariant={}", document.getId(), e.getInvariant());
                continue;
            } catch (IOException | RuntimeException e) {
                // the file stays for the next run; the document is not marked
                failed++;
                log.warn("fiscal.xml.purge.document.failed documentId={} error={}", document.getId(), e.getClass().getSimpleName());
                continue;
            }
            document.markXmlPurged(now);
            // a failing save ends the page's transaction: the page is retried by the next run (purge())
            documentRepository.saveAndFlush(document);
            purged++;
        }
        return new PageResult(documents.size(), purged, failed, lastId);
    }

    private void purgeFiles(FiscalDocument document) throws IOException {
        fileStorage.delete(document.getXmlFilePath());
        if (document instanceof GoodsInvoice goods) {
            fileStorage.delete(goods.getRectifiedFilePath());
        }
    }
}
