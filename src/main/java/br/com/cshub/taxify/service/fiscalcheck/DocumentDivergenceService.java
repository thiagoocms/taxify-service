package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO.DivergenceSummary;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The fiscal checks ({@link FiscalCheckEngine}) of a set of notes, summed up per note - what the
 * "Notas fiscais" list shows as the divergence badge and filters on. Same checks as the
 * conferência screen, run on demand (nothing is stored): items are read in chunks, one context
 * (company rules + IBS/CBS rates) for the whole set.
 */
@Service
public class DocumentDivergenceService {

    /** IN-list size per items query. */
    private static final int CHUNK = 500;
    /** Messages kept per note for the badge tooltip. */
    private static final int MAX_MESSAGES = 5;

    private final FiscalXmlDocumentItemRepository itemRepository;
    private final FiscalCheckEngine checkEngine;

    public DocumentDivergenceService(FiscalXmlDocumentItemRepository itemRepository, FiscalCheckEngine checkEngine) {
        this.itemRepository = itemRepository;
        this.checkEngine = checkEngine;
    }

    /** Notes with nothing found (or canceled/deleted, never checked) are left out of the map. */
    @Transactional(readOnly = true)
    public Map<UUID, DivergenceSummary> summarize(UUID companyId, Collection<FiscalXmlDocument> documents) {
        Map<UUID, FiscalXmlDocument> checkable = new HashMap<>();
        for (FiscalXmlDocument document : documents) {
            if (FiscalCheckEngine.isCheckable(document)) {
                checkable.put(document.getId(), document);
            }
        }
        Map<UUID, DivergenceSummary> summaries = new HashMap<>();
        if (checkable.isEmpty()) {
            return summaries;
        }

        Map<UUID, List<FiscalXmlDocumentItem>> itemsByDocument = new HashMap<>();
        List<UUID> ids = new ArrayList<>(checkable.keySet());
        for (int start = 0; start < ids.size(); start += CHUNK) {
            for (FiscalXmlDocumentItem item : itemRepository.findByDocument_IdIn(ids.subList(start, Math.min(ids.size(), start + CHUNK)))) {
                itemsByDocument.computeIfAbsent(item.getDocument().getId(), key -> new ArrayList<>()).add(item);
            }
        }

        FiscalCheckContext context = checkEngine.contextFor(companyId);
        checkable.forEach((id, document) -> {
            List<FiscalXmlDocumentItem> items = itemsByDocument.getOrDefault(id, List.of());
            List<FiscalDivergence> divergences = new ArrayList<>(checkEngine.checkDocument(document, items, context));
            for (FiscalXmlDocumentItem item : items) {
                divergences.addAll(checkEngine.check(item, context));
            }
            if (!divergences.isEmpty()) {
                summaries.put(id, summary(divergences));
            }
        });
        return summaries;
    }

    private static DivergenceSummary summary(List<FiscalDivergence> divergences) {
        int errors = 0;
        Set<String> messages = new LinkedHashSet<>();
        for (FiscalDivergence divergence : divergences) {
            if (divergence.severity() == DivergenceSeverity.ERROR) {
                errors++;
            }
            if (messages.size() < MAX_MESSAGES && divergence.message() != null) {
                messages.add(divergence.message());
            }
        }
        return new DivergenceSummary(errors, divergences.size() - errors, List.copyOf(messages));
    }
}
