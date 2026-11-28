package br.com.cshub.taxify.domain.fiscalrule;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A company's {@link ProductFiscalRule}s, looked up the way a received note needs them: the
 * product code on it is the <em>supplier's</em> code, so a rule may be pinned to one supplier
 * ({@code documentoFornecedor}). The supplier's own rule wins; otherwise the general rule of the
 * code (no supplier) applies. Rules without a product code are left out. The code and the supplier are compared exactly
 * (case and spaces count); the supplier by its digits.
 */
public final class ProductRules {

    private static final ProductRules NONE = new ProductRules(List.of());

    private final Map<String, ProductFiscalRule> general = new HashMap<>();
    private final Map<String, ProductFiscalRule> bySupplier = new HashMap<>();

    /** Two rules for the same key (the screen refuses them; old data may have them): the lowest id wins, as in V87. */
    private static final Comparator<ProductFiscalRule> BY_ID =
            Comparator.comparing(rule -> rule.getId() == null ? "" : rule.getId().toString());

    private ProductRules(Collection<ProductFiscalRule> rules) {
        List<ProductFiscalRule> ordered = new ArrayList<>(rules);
        ordered.sort(BY_ID);
        for (ProductFiscalRule rule : ordered) {
            if (rule.getProductCode() == null) {
                continue;
            }
            String supplier = DocumentNumbers.onlyDigits(rule.getSupplierDocument());
            if (supplier == null || supplier.isEmpty()) {
                general.putIfAbsent(rule.getProductCode(), rule);
            } else {
                bySupplier.putIfAbsent(key(rule.getProductCode(), supplier), rule);
            }
        }
    }

    public static ProductRules of(Collection<ProductFiscalRule> rules) {
        return rules.isEmpty() ? NONE : new ProductRules(rules);
    }

    public static ProductRules none() {
        return NONE;
    }

    public boolean isEmpty() {
        return general.isEmpty() && bySupplier.isEmpty();
    }

    /** The rule of {@code codigo} for that supplier, or the code's general rule. */
    public Optional<ProductFiscalRule> find(String codigo, String supplierDocument) {
        if (codigo == null) {
            return Optional.empty();
        }
        String supplier = DocumentNumbers.onlyDigits(supplierDocument);
        if (supplier != null && !supplier.isEmpty()) {
            ProductFiscalRule own = bySupplier.get(key(codigo, supplier));
            if (own != null) {
                return Optional.of(own);
            }
        }
        return Optional.ofNullable(general.get(codigo));
    }

    /** The rule for the item, by its code and the note's issuer. */
    public Optional<ProductFiscalRule> forItem(FiscalDocumentItem item) {
        return find(item.getProduct().code(), supplierOf(item.getDocument()));
    }

    /** CNPJ (or CPF, a rural producer) of the note's issuer - the supplier on a received note. */
    public static String supplierOf(FiscalDocument document) {
        if (document == null) {
            return null;
        }
        return document.getIssuer().document();
    }

    private static String key(String codigo, String supplier) {
        return codigo + '|' + supplier;
    }
}
