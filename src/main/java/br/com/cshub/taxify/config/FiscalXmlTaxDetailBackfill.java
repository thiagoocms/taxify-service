package br.com.cshub.taxify.config;

import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.integration.fiscalxml.NfeRetentions;
import br.com.cshub.taxify.mapper.fiscaldocument.FiscalDocumentFactory;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlImpostoItemDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlProdutoDTO;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadata;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Re-reads the stored XML of notes parsed by an older {@link FiscalXmlMetadataParser}
 * ({@code metadata_version} below {@link FiscalXmlMetadataParser#CURRENT_VERSION}) and
 * fills in only what that version didn't extract or got wrong - the items' tax detail,
 * IBS/CBS and IPI CST/value (always empty before version 3), the note's IBS/CBS totals
 * and the header extras of version 4.
 * Nothing else is touched: no item is recreated, so ids, review
 * state and audit history stay as they are. Always reads the original file, never the
 * rectified copy - the checks are about what was actually issued.
 *
 * <p>Runs once in the background after startup and does nothing once every note is on the
 * current version. A note whose file was purged (or can't be parsed) is marked as done -
 * there is nothing more to read from it; one that fails to read (storage down) is left for
 * the next startup. Off with {@code fiscal-xml.tax-detail-backfill.enabled=false}.
 */
@Component
public class FiscalXmlTaxDetailBackfill {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlTaxDetailBackfill.class);
    private static final int BATCH = 200;

    private final boolean enabled;
    private final GoodsInvoiceRepository repository;
    private final FiscalXmlFileStorage storage;
    private final FiscalXmlMetadataParser parser;
    private final TransactionTemplate transactionTemplate;
    /** Set on shutdown - the run stops between notes instead of failing on a closed EntityManagerFactory. */
    private volatile boolean stopping;

    public FiscalXmlTaxDetailBackfill(@Value("${fiscal-xml.tax-detail-backfill.enabled:true}") boolean enabled,
                                      GoodsInvoiceRepository repository, FiscalXmlFileStorage storage,
                                      FiscalXmlMetadataParser parser, TransactionTemplate transactionTemplate) {
        this.enabled = enabled;
        this.repository = repository;
        this.storage = storage;
        this.parser = parser;
        this.transactionTemplate = transactionTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startInBackground() {
        if (enabled) {
            Thread.ofVirtual().name("fiscal-xml-tax-detail-backfill").start(this::backfillAll);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        stopping = true;
    }

    enum Outcome { UPDATED, MISSING, UNREADABLE, FAILED }

    void backfillAll() {
        Map<Outcome, Integer> totals = new HashMap<>();
        String after = "";
        while (!stopping) {
            List<UUID> ids = repository.findIdsParsedBefore(FiscalXmlMetadataParser.CURRENT_VERSION, after, PageRequest.of(0, BATCH));
            if (ids.isEmpty()) {
                break;
            }
            if (totals.isEmpty()) {
                log.info("Re-reading stored NFe XMLs to fill in the item tax detail and IBS/CBS...");
            }
            for (UUID id : ids) {
                if (stopping) {
                    log.info("Tax detail backfill interrupted by shutdown: {} - resumes on the next startup", totals);
                    return;
                }
                totals.merge(backfill(id), 1, Integer::sum);
            }
            after = ids.getLast().toString();
            log.info("Tax detail backfill: {} notes so far", totals);
        }
        if (stopping) {
            log.info("Tax detail backfill interrupted by shutdown: {} - resumes on the next startup", totals);
        } else if (!totals.isEmpty()) {
            log.info("Tax detail backfill finished: {}{}", totals,
                    totals.getOrDefault(Outcome.FAILED, 0) > 0 ? " - restart to retry the failed ones" : "");
        }
    }

    Outcome backfill(UUID documentId) {
        try {
            return transactionTemplate.execute(status -> {
                GoodsInvoice document = repository.findById(documentId).orElse(null);
                if (document == null) {
                    return Outcome.MISSING;
                }
                byte[] xml;
                try {
                    xml = storage.read(document.getXmlFilePath());
                } catch (NoSuchFileException e) {
                    markDone(document);
                    return Outcome.MISSING;
                } catch (IOException e) {
                    log.warn("Could not read {} (note {}) - left for the next run", document.getXmlFilePath(), documentId, e);
                    return Outcome.FAILED;
                }
                FiscalXmlMetadata metadata = parser.parse(xml);
                if (!FiscalXmlMetadata.NFE.equals(metadata.documentType())) {
                    log.warn("Note {} could not be parsed as NFe - marked as done without tax detail", documentId);
                    markDone(document);
                    return Outcome.UNREADABLE;
                }
                apply(document, metadata);
                document.setWithholdings(NfeRetentions.parse(xml).toWithholdings());
                markDone(document);
                return Outcome.UPDATED;
            });
        } catch (RuntimeException e) {
            log.warn("Tax detail backfill failed for note {}", documentId, e);
            return Outcome.FAILED;
        }
    }

    /** Only the fields the older parser didn't have or got wrong - items matched by nItem, else by position. */
    static void apply(GoodsInvoice document, FiscalXmlMetadata metadata) {
        FiscalDocumentFactory.refreshHeaderExtras(document, metadata);
        if (metadata.totais() != null) {
            document.setIbsCbsTotals(FiscalDocumentFactory.ibsCbsTotals(metadata.totais().ibsCbs()));
        }
        List<FiscalXmlProdutoDTO> produtos = metadata.produtos();
        Map<Integer, FiscalXmlProdutoDTO> byNumber = new HashMap<>();
        produtos.forEach(produto -> {
            if (produto.numeroItem() != null) {
                byNumber.putIfAbsent(produto.numeroItem(), produto);
            }
        });
        List<FiscalDocumentItem> items = document.getItems();
        for (int i = 0; i < items.size(); i++) {
            FiscalDocumentItem item = items.get(i);
            FiscalXmlProdutoDTO produto = item.getItemNumber() != null
                    ? byNumber.get(item.getItemNumber())
                    : (i < produtos.size() ? produtos.get(i) : null);
            FiscalXmlImpostoItemDTO impostos = produto == null ? null : produto.impostos();
            if (impostos != null) {
                FiscalDocumentFactory.refreshTaxDetail(item, impostos);
            }
        }
    }

    private void markDone(GoodsInvoice document) {
        document.setMetadataVersion(FiscalXmlMetadataParser.CURRENT_VERSION);
        repository.save(document);
    }
}
