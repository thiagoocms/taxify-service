package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.dto.fiscalrule.FiscalCheckFindingDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckContext;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckEngine;
import br.com.cshub.taxify.service.fiscalcheck.FiscalDivergence;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    private final FiscalXmlDocumentItemRepository itemRepository;
    private final FiscalCheckEngine checkEngine;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    public FiscalConferenceService(FiscalXmlDocumentItemRepository itemRepository, FiscalCheckEngine checkEngine,
                                   CompanyValidation companyValidation, MessageService messageService) {
        this.itemRepository = itemRepository;
        this.checkEngine = checkEngine;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public List<FiscalCheckFindingDTO> findFindings(String companyId, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim) {
        UUID id = parseCompanyId(companyId);
        companyValidation.checkExist(id);

        LocalDateTime from = dataEmissaoInicio != null ? dataEmissaoInicio.atStartOfDay() : LocalDateTime.MIN;
        LocalDateTime to = dataEmissaoFim != null ? dataEmissaoFim.atTime(LocalTime.MAX) : LocalDateTime.MAX;

        Map<FiscalXmlDocument, List<FiscalXmlDocumentItem>> itemsByDocument = new LinkedHashMap<>();
        for (FiscalXmlDocumentItem item : itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(id, from, to)) {
            FiscalXmlDocument document = item.getDocument();
            if (!document.isDeleted() && !document.isCanceled()) {
                itemsByDocument.computeIfAbsent(document, key -> new ArrayList<>()).add(item);
            }
        }

        FiscalCheckContext context = checkEngine.contextFor(id);
        List<FiscalCheckFindingDTO> findings = new ArrayList<>();
        itemsByDocument.forEach((document, items) -> {
            checkEngine.checkDocument(document, items, context)
                    .forEach(divergence -> findings.add(toDTO(document, null, divergence)));
            for (FiscalXmlDocumentItem item : items) {
                checkEngine.check(item, context).forEach(divergence -> findings.add(toDTO(document, item, divergence)));
            }
        });
        return findings;
    }

    private FiscalCheckFindingDTO toDTO(FiscalXmlDocument document, FiscalXmlDocumentItem item, FiscalDivergence divergence) {
        return new FiscalCheckFindingDTO(
                document.getId().toString(),
                document.getAccessKey(),
                document.getIssueDate(),
                document.getEmitenteNome(),
                item == null ? null : item.getNumeroItem(),
                item == null ? null : item.getCodigo(),
                item == null ? null : item.getDescricao(),
                divergence.code(),
                divergence.field(),
                divergence.found(),
                divergence.expected(),
                divergence.severity(),
                divergence.message()
        );
    }

    private UUID parseCompanyId(String companyId) {
        try {
            return UUID.fromString(companyId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
    }
}
