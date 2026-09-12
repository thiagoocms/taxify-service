package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.dto.fiscalrule.ProductFiscalDivergenceDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Compares already-downloaded/stored {@link FiscalXmlDocumentItem}s against the
 * {@link ProductFiscalRule} registered for each product, to find notes that were
 * issued with the wrong CFOP/NCM and need rectifying - the actual correction still
 * happens upstream (in the issuing system), this only tells you what to fix.
 */
@Service
public class ProductFiscalDivergenceService {

    private final FiscalXmlDocumentItemRepository itemRepository;
    private final ProductFiscalRuleRepository ruleRepository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    public ProductFiscalDivergenceService(FiscalXmlDocumentItemRepository itemRepository, ProductFiscalRuleRepository ruleRepository, CompanyValidation companyValidation, MessageService messageService) {
        this.itemRepository = itemRepository;
        this.ruleRepository = ruleRepository;
        this.companyValidation = companyValidation;

        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public List<ProductFiscalDivergenceDTO> findDivergences(String companyId, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim) {
        UUID id = parseCompanyId(companyId);
        companyValidation.checkExist(id);

        LocalDateTime from = dataEmissaoInicio != null ? dataEmissaoInicio.atStartOfDay() : LocalDateTime.MIN;
        LocalDateTime to = dataEmissaoFim != null ? dataEmissaoFim.atTime(LocalTime.MAX) : LocalDateTime.MAX;

        List<FiscalXmlDocumentItem> items = itemRepository.findByDocument_Company_IdAndDocument_IssueDateBetween(id, from, to);

        List<ProductFiscalDivergenceDTO> divergences = new ArrayList<>();
        for (FiscalXmlDocumentItem item : items) {
            if (item.getCodigo() == null) {
                continue;
            }
            ruleRepository.findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(id, item.getCodigo())
                    .filter(rule -> diverges(item, rule))
                    .ifPresent(rule -> divergences.add(toDivergenceDTO(item, rule)));
        }
        return divergences;
    }

    private boolean diverges(FiscalXmlDocumentItem item, ProductFiscalRule rule) {
        boolean cfopDiverges = !equalsTrimmed(item.getCfop(), rule.getCfopCorreto());
        boolean ncmDiverges = rule.getNcmCorreto() != null && !equalsTrimmed(item.getNcm(), rule.getNcmCorreto());
        return cfopDiverges || ncmDiverges;
    }

    private boolean equalsTrimmed(String a, String b) {
        return Objects.equals(a == null ? null : a.trim(), b == null ? null : b.trim());
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
                rule.getCfopCorreto(),
                item.getNcm(),
                rule.getNcmCorreto()
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
