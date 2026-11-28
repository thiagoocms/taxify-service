package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.IssuePeriod;
import br.com.cshub.taxify.common.RequestIds;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.dto.fiscalrule.FiscalCheckFindingDTO;
import br.com.cshub.taxify.repository.FiscalDocumentItemRepository;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckContext;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckEngine;
import br.com.cshub.taxify.service.fiscalcheck.FiscalDivergence;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The fiscal conferência of a company's notes in a period: every {@link FiscalCheckEngine}
 * check over every item and every note, one row per finding. Canceled and deleted notes
 * are left out - there is nothing to fix on them.
 */
@Service
public class FiscalConferenceService {

    private final FiscalDocumentItemRepository itemRepository;
    private final FiscalCheckEngine checkEngine;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    public FiscalConferenceService(FiscalDocumentItemRepository itemRepository, FiscalCheckEngine checkEngine,
                                   CompanyValidation companyValidation, MessageService messageService) {
        this.itemRepository = itemRepository;
        this.checkEngine = checkEngine;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public List<FiscalCheckFindingDTO> findFindings(String companyId, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim) {
        UUID id = RequestIds.parse(companyId, messageService);
        companyValidation.checkExist(id);

        LocalDateTime from = IssuePeriod.start(dataEmissaoInicio);
        LocalDateTime to = IssuePeriod.endExclusive(dataEmissaoFim);

        Map<GoodsInvoice, List<FiscalDocumentItem>> itemsByDocument = new LinkedHashMap<>();
        for (FiscalDocumentItem item : itemRepository.findIssuedInPeriodOrdered(id, from, to)) {
            GoodsInvoice document = item.getDocument();
            if (FiscalCheckEngine.isCheckable(document)) {
                itemsByDocument.computeIfAbsent(document, key -> new ArrayList<>()).add(item);
            }
        }

        FiscalCheckContext context = checkEngine.contextFor(id);
        List<FiscalCheckFindingDTO> findings = new ArrayList<>();
        itemsByDocument.forEach((document, items) -> {
            checkEngine.checkDocument(document, items, context)
                    .forEach(divergence -> findings.add(toDTO(document, null, divergence)));
            for (FiscalDocumentItem item : items) {
                checkEngine.check(item, context).forEach(divergence -> findings.add(toDTO(document, item, divergence)));
            }
        });
        return findings;
    }

    private FiscalCheckFindingDTO toDTO(GoodsInvoice document, FiscalDocumentItem item, FiscalDivergence divergence) {
        return new FiscalCheckFindingDTO(
                document.getId().toString(),
                document.getAccessKey(),
                document.getIssueDate(),
                document.getIssuer().name(),
                item == null ? null : item.getItemNumber(),
                item == null ? null : item.getProduct().code(),
                item == null ? null : item.getProduct().description(),
                divergence.code(),
                divergence.field(),
                divergence.found(),
                divergence.expected(),
                divergence.severity(),
                divergence.message()
        );
    }
}
