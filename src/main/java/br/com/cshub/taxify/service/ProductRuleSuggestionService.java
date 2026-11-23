package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.RequestIds;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO;
import br.com.cshub.taxify.dto.fiscalrule.ProductRuleSuggestionDTO.SuggestedValue;
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
 * Suggests each product's fiscal rule from the notes the company received (it is the
 * recipient - the notes the rules rectify): for every field, the value the items used most
 * often, with how consistent the notes are about it - so the accountant confirms a prefilled
 * rule instead of typing every product. The supplier's exit CFOP becomes the equivalent entry
 * CFOP ({@link EntryCfops}: 5102 -> 1102); the supplier's one is returned alongside. One
 * aggregated query per company; the mode of each field is computed here.
 */
@Service
public class ProductRuleSuggestionService {

    /** Columns of FiscalXmlDocumentItemRepository#findReceivedClassificationsByProduct, in order, as rule field names. */
    private static final String[] FIELDS = {
            "ncmCorreto", "cfopCorreto", "origemMercadoria", "cstIcms", "csosn", "aliquotaIcms", "cest",
            "cstIpi", "aliquotaIpi", "cstPis", "aliquotaPis", "cstCofins", "aliquotaCofins",
            "cstIbsCbs", "classificacaoTributaria"};
    private static final int FIRST_FIELD = 2;
    /** Index of cfopCorreto in FIELDS. */
    private static final int CFOP = 1;
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
     * @param onlyWithoutRule leave out products that already have a rule
     * @param codigoProduto   only this product (the rule form); null = every product
     * @return up to 500 products, most frequent first
     */
    @Transactional(readOnly = true)
    public List<ProductRuleSuggestionDTO> suggest(String companyId, boolean onlyWithoutRule, String codigoProduto) {
        UUID id = RequestIds.parse(companyId, messageService);
        companyValidation.checkExist(id);
        String onlyCode = codigoProduto == null || codigoProduto.isBlank() ? null : codigoProduto.strip();

        Map<String, Product> products = new LinkedHashMap<>();
        for (Object[] row : itemRepository.findReceivedClassificationsByProduct(id)) {
            String codigo = (String) row[0];
            if (onlyCode != null && !onlyCode.equals(codigo)) {
                continue;
            }
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
        private final Map<String, Long> supplierCfops = new HashMap<>();

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
                if (value == null) {
                    continue;
                }
                if (i == CFOP) {
                    supplierCfops.merge(value, count, Long::sum);
                    value = EntryCfops.fromSupplier(value);
                }
                counts.get(i).merge(value, count, Long::sum);
            }
        }

        ProductRuleSuggestionDTO toDTO(boolean hasRule) {
            Map<String, SuggestedValue> fields = new LinkedHashMap<>();
            for (int i = 0; i < FIELDS.length; i++) {
                SuggestedValue mode = mode(counts.get(i));
                if (mode != null) {
                    fields.put(FIELDS[i], mode);
                }
            }
            return new ProductRuleSuggestionDTO(codigo, descricao, total, hasRule, fields, mode(supplierCfops));
        }

        private static SuggestedValue mode(Map<String, Long> byValue) {
            if (byValue.isEmpty()) {
                return null;
            }
            long withField = byValue.values().stream().mapToLong(Long::longValue).sum();
            Map.Entry<String, Long> mode = byValue.entrySet().stream()
                    .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .orElseThrow();
            BigDecimal share = BigDecimal.valueOf(mode.getValue()).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(withField), 1, RoundingMode.HALF_UP);
            return new SuggestedValue(mode.getKey(), mode.getValue(), share);
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
}
