package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.domain.fiscalrule.ProductRules;
import br.com.cshub.taxify.service.fiscalcheck.OperationCfops;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** Row index of the supplier's CNPJ/CPF; its name comes next, then the count. */
    private static final int SUPPLIER = FIRST_FIELD + FIELDS.length;
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
     * @param fornecedor      with {@code codigoProduto}: only this supplier's (CNPJ/CPF); null = any
     * @return up to 500 supplier products, most frequent first - one per supplier and code, since
     *         the code on a received note is the supplier's
     */
    @Transactional(readOnly = true)
    public List<ProductRuleSuggestionDTO> suggest(String companyId, boolean onlyWithoutRule, String codigoProduto, String fornecedor) {
        UUID id = RequestIds.parse(companyId, messageService);
        companyValidation.checkExist(id);
        String onlyCode = codigoProduto == null || codigoProduto.isBlank() ? null : codigoProduto.strip();
        String onlySupplier = DocumentNumbers.onlyDigits(fornecedor);
        if (onlySupplier != null && onlySupplier.isEmpty()) {
            onlySupplier = null;
        }

        Map<String, Product> products = new LinkedHashMap<>();
        for (Object[] row : itemRepository.findReceivedClassificationsByProduct(id)) {
            String codigo = (String) row[0];
            String supplier = DocumentNumbers.onlyDigits((String) row[SUPPLIER]);
            if ((onlyCode != null && !onlyCode.equals(codigo)) || (onlySupplier != null && !onlySupplier.equals(supplier))) {
                continue;
            }
            long count = ((Number) row[row.length - 1]).longValue();
            Product product = products.computeIfAbsent(codigo + '|' + supplier, key -> new Product(codigo, supplier));
            product.add((String) row[1], (String) row[SUPPLIER + 1], row, count);
        }
        ProductRules rules = products.isEmpty() ? ProductRules.none()
                : ProductRules.of(ruleRepository.findByCompany_IdAndDeletedIsFalse(id));

        return products.values().stream()
                .filter(product -> !onlyWithoutRule || !product.hasRule(rules))
                .sorted(Comparator.comparingLong((Product product) -> product.total).reversed()
                        .thenComparing(product -> product.codigo)
                        .thenComparing(product -> product.supplier == null ? "" : product.supplier))
                .limit(MAX_PRODUCTS)
                .map(product -> product.toDTO(product.hasRule(rules)))
                .toList();
    }

    /** Counts per value of each field across the product's combinations. */
    private static final class Product {
        private final String codigo;
        private final String supplier;
        private String supplierName;
        private String descricao;
        private long descricaoCount;
        private long total;
        private final List<Map<String, Long>> counts = new ArrayList<>();
        private final Map<String, Long> supplierCfops = new HashMap<>();

        Product(String codigo, String supplier) {
            this.codigo = codigo;
            this.supplier = supplier;
            for (int i = 0; i < FIELDS.length; i++) {
                counts.add(new HashMap<>());
            }
        }

        boolean hasRule(ProductRules rules) {
            return rules.find(codigo, supplier).isPresent();
        }

        void add(String descricao, String supplierName, Object[] row, long count) {
            total += count;
            if (supplierName != null) {
                this.supplierName = supplierName;
            }
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
                    // Kept as an internal purchase (1xxx): the rule adapts it to each note's operation.
                    value = OperationCfops.forOperation(EntryCfops.fromSupplier(value), "1");
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
            return new ProductRuleSuggestionDTO(codigo, descricao, total, hasRule, fields, mode(supplierCfops),
                    supplier, supplierName);
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
