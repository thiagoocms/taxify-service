package br.com.cshub.taxify.domain.fiscalrule;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A company's {@link ProductFiscalRule}s, looked up the way a received note needs them: the
 * product code on it is the <em>supplier's</em> code, so a rule may be pinned to one supplier
 * ({@code documentoFornecedor}). The supplier's own rule wins; otherwise the general rule of the
 * code (no supplier) applies. Rules without a product code are left out.
 */
public final class ProductRules {

    private static final ProductRules NONE = new ProductRules(List.of());

    private final Map<String, ProductFiscalRule> general = new HashMap<>();
    private final Map<String, ProductFiscalRule> bySupplier = new HashMap<>();

    private ProductRules(Collection<ProductFiscalRule> rules) {
        for (ProductFiscalRule rule : rules) {
            if (rule.getCodigoProduto() == null) {
                continue;
            }
            String supplier = DocumentNumbers.onlyDigits(rule.getDocumentoFornecedor());
            if (supplier == null || supplier.isEmpty()) {
                general.putIfAbsent(rule.getCodigoProduto(), rule);
            } else {
                bySupplier.putIfAbsent(key(rule.getCodigoProduto(), supplier), rule);
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
    public Optional<ProductFiscalRule> forItem(FiscalXmlDocumentItem item) {
        return find(item.getCodigo(), supplierOf(item.getDocument()));
    }

    /** CNPJ (or CPF, a rural producer) of the note's issuer - the supplier on a received note. */
    public static String supplierOf(FiscalXmlDocument document) {
        if (document == null) {
            return null;
        }
        return document.getIssuerCnpj() != null && !document.getIssuerCnpj().isBlank()
                ? document.getIssuerCnpj() : document.getEmitenteCpf();
    }

    private static String key(String codigo, String supplier) {
        return codigo + '|' + supplier;
    }
}
