package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.dto.fiscalrule.CompanyProductDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Taxify has no product master data of its own: a company's products are the distinct
 * product codes found in the items of its stored notes. This is what the rule form picks
 * from, so a rule is always registered against a code that actually shows up on the notes.
 */
@Service
public class CompanyProductService {

    private static final int MAX_RESULTS = 20;

    private final FiscalXmlDocumentItemRepository itemRepository;
    private final ProductFiscalRuleRepository ruleRepository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    public CompanyProductService(FiscalXmlDocumentItemRepository itemRepository, ProductFiscalRuleRepository ruleRepository,
                                 CompanyValidation companyValidation, MessageService messageService) {
        this.itemRepository = itemRepository;
        this.ruleRepository = ruleRepository;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
    }

    /** Up to 20 products whose code or description contains {@code search} (any, when blank). */
    @Transactional(readOnly = true)
    public List<CompanyProductDTO> search(String companyId, String search) {
        UUID id = parseCompanyId(companyId);
        companyValidation.checkExist(id);

        String term = "%" + (search == null ? "" : search.trim().toLowerCase(Locale.ROOT)) + "%";
        List<Object[]> rows = itemRepository.findDistinctProductsByCompany(id, term, PageRequest.of(0, MAX_RESULTS));
        if (rows.isEmpty()) {
            return List.of();
        }

        List<String> codigos = rows.stream().map(row -> (String) row[0]).toList();
        Set<String> withRule = new HashSet<>(ruleRepository.findCodigosWithRule(id, codigos));

        return rows.stream().map(row -> {
            String codigo = (String) row[0];
            String descricao = (String) row[1];
            return new CompanyProductDTO(
                    codigo,
                    descricao,
                    (String) row[2],
                    (String) row[3],
                    ((Number) row[4]).longValue(),
                    withRule.contains(codigo),
                    descricao == null || descricao.isBlank() ? codigo : codigo + " - " + descricao
            );
        }).toList();
    }

    private UUID parseCompanyId(String companyId) {
        try {
            return UUID.fromString(companyId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
    }
}
