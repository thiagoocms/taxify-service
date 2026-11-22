package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO.SuggestedValue;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Suggests each product's fiscal rule from its notes: for every field, the value the items
 * used most often, with how consistent the notes are about it - so the accountant confirms a
 * prefilled rule instead of typing every product. One aggregated query per company; the
 * mode of each field is computed here.
 */
@Service
public class ProductRuleSuggestionService {

    /** Columns of FiscalXmlDocumentItemRepository#findClassificationsByProduct, in order, as rule field names. */
    private static final String[] FIELDS = {
            "ncmCorreto", "cfopCorreto", "origemMercadoria", "cstIcms", "csosn", "aliquotaIcms", "cest",
            "cstIpi", "aliquotaIpi", "cstPis", "aliquotaPis", "cstCofins", "aliquotaCofins",
            "cstIbsCbs", "classificacaoTributaria"};
    private static final int FIRST_FIELD = 2;
    private static final int MAX_PRODUCTS = 500;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final FiscalXmlDocumentItemRepository itemRepository;
    private final ProductFiscalRuleRepository ruleRepository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    public ProductRuleSuggestionService(FiscalXmlDocumentItemRepository itemRepository, ProductFiscalRuleRepository ruleRepository,
                                        CompanyValidation companyValidation, MessageService messageService) {
        this.itemRepository = itemRepository;
        this.ruleRepository = ruleRepository;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
    }

    /**
     * @param direction       only notes of this direction (OUTBOUND = the company's own sales); null = all
     * @param onlyWithoutRule leave out products that already have a rule
     * @return up to 500 products, most frequent first
     */
    @Transactional(readOnly = true)
    public List<ProductRuleSuggestionDTO> suggest(String companyId, FiscalDirection direction, boolean onlyWithoutRule) {
        UUID id = parseCompanyId(companyId);
        companyValidation.checkExist(id);

        Map<String, Product> products = new LinkedHashMap<>();
        for (Object[] row : itemRepository.findClassificationsByProduct(id, direction)) {
            String codigo = (String) row[0];
            long count = ((Number) row[row.length - 1]).longValue();
            Product product = products.computeIfAbsent(codigo, Product::new);
            product.add((String) row[1], row, count);
        }
        Set<String> withRule = products.isEmpty() ? Set.of()
                : new HashSet<>(ruleRepository.findCodigosWithRule(id, products.keySet()));

        return products.values().stream()
                .filter(product -> !onlyWithoutRule || !withRule.contains(product.codigo))
                .sorted(Comparator.comparingLong((Product product) -> product.total).reversed().thenComparing(product -> product.codigo))
                .limit(MAX_PRODUCTS)
                .map(product -> product.toDTO(withRule.contains(product.codigo)))
                .toList();
    }

    /** Counts per value of each field across the product's combinations. */
    private static final class Product {
        private final String codigo;
        private String descricao;
        private long descricaoCount;
        private long total;
        private final List<Map<String, Long>> counts = new ArrayList<>();

        Product(String codigo) {
            this.codigo = codigo;
            for (int i = 0; i < FIELDS.length; i++) {
                counts.add(new HashMap<>());
            }
        }

        void add(String descricao, Object[] row, long count) {
            total += count;
            if (descricao != null && count > descricaoCount) {
                this.descricao = descricao;
                this.descricaoCount = count;
            }
            for (int i = 0; i < FIELDS.length; i++) {
                String value = asText(row[FIRST_FIELD + i]);
                if (value != null) {
                    counts.get(i).merge(value, count, Long::sum);
                }
            }
        }

        ProductRuleSuggestionDTO toDTO(boolean hasRule) {
            Map<String, SuggestedValue> fields = new LinkedHashMap<>();
            for (int i = 0; i < FIELDS.length; i++) {
                Map<String, Long> byValue = counts.get(i);
                if (byValue.isEmpty()) {
                    continue;
                }
                long withField = byValue.values().stream().mapToLong(Long::longValue).sum();
                Map.Entry<String, Long> mode = byValue.entrySet().stream()
                        .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                        .orElseThrow();
                BigDecimal share = BigDecimal.valueOf(mode.getValue()).multiply(HUNDRED)
                        .divide(BigDecimal.valueOf(withField), 1, RoundingMode.HALF_UP);
                fields.put(FIELDS[i], new SuggestedValue(mode.getKey(), mode.getValue(), share));
            }
            return new ProductRuleSuggestionDTO(codigo, descricao, total, hasRule, fields);
        }
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private UUID parseCompanyId(String companyId) {
        try {
            return UUID.fromString(companyId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
    }
}
