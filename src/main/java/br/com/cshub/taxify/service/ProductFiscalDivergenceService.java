package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.IssuePeriod;
import br.com.cshub.taxify.common.RequestIds;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalDivergenceDTO;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckContext;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckEngine;
import br.com.cshub.taxify.service.fiscalcheck.ProductRuleCheck;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Compares already-downloaded/stored {@link FiscalXmlDocumentItem}s against the
 * {@link ProductFiscalRule} registered for each product, to find notes that were
 * issued with the wrong CFOP/NCM and need rectifying - the actual correction still
 * happens upstream (in the issuing system), this only tells you what to fix.
 *
 * <p>The comparison itself is {@link ProductRuleCheck}, run through {@link FiscalCheckEngine};
 * this response keeps its original shape (one row per item, current x correct CFOP/NCM), so
 * only that check's findings go into it - the other checks get their own response.
 */
@Service
public class ProductFiscalDivergenceService {

    private static final Set<String> RULE_CODES = Set.of(ProductRuleCheck.CFOP, ProductRuleCheck.NCM);

    private final FiscalXmlDocumentItemRepository itemRepository;
    private final FiscalCheckEngine checkEngine;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    public ProductFiscalDivergenceService(FiscalXmlDocumentItemRepository itemRepository, FiscalCheckEngine checkEngine,
                                          CompanyValidation companyValidation, MessageService messageService) {
        this.itemRepository = itemRepository;
        this.checkEngine = checkEngine;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public List<ProductFiscalDivergenceDTO> findDivergences(String companyId, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim) {
        UUID id = RequestIds.parse(companyId, messageService);
        companyValidation.checkExist(id);

        LocalDateTime from = IssuePeriod.start(dataEmissaoInicio);
        LocalDateTime to = IssuePeriod.end(dataEmissaoFim);

        List<FiscalXmlDocumentItem> items = itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(id, from, to);
        FiscalCheckContext context = checkEngine.contextFor(id);

        List<ProductFiscalDivergenceDTO> divergences = new ArrayList<>();
        for (FiscalXmlDocumentItem item : items) {
            if (!FiscalCheckEngine.isCheckable(item.getDocument())) {
                continue;
            }
            boolean diverges = checkEngine.check(item, context).stream()
                    .anyMatch(divergence -> RULE_CODES.contains(divergence.code()));
            if (diverges) {
                context.ruleFor(item).ifPresent(rule -> divergences.add(toDivergenceDTO(item, rule)));
            }
        }
        return divergences;
    }

    private ProductFiscalDivergenceDTO toDivergenceDTO(FiscalXmlDocumentItem item, ProductFiscalRule rule) {
        FiscalXmlDocument document = item.getDocument();
        return new ProductFiscalDivergenceDTO(
                document.getId().toString(),
                document.getAccessKey(),
                document.getIssueDate(),
                item.getCodigo(),
                item.getDescricao(),
                item.getCfop(),
                ProductRuleCheck.expectedCfop(item, rule),
                item.getNcm(),
                rule.getNcmCorreto()
        );
    }

}
