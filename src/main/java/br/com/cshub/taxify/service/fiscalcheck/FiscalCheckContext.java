package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.fiscalrule.ProductRules;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.reform.IbsCbsRate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the checks of one company share, loaded once per run instead of once per item:
 * the company's product rules ({@link ProductRules}: by code and supplier) and the IBS/CBS
 * reference rates by year.
 */
public record FiscalCheckContext(UUID companyId, ProductRules rules, Map<Integer, IbsCbsRate> ibsCbsRatesByYear) {

    public FiscalCheckContext {
        ibsCbsRatesByYear = Map.copyOf(ibsCbsRatesByYear);
    }

    /** General rules (no supplier) keyed by product code - for tests and simple callers. */
    public FiscalCheckContext(UUID companyId, Map<String, ProductFiscalRule> rulesByProduct, Map<Integer, IbsCbsRate> ibsCbsRatesByYear) {
        this(companyId, ProductRules.of(rulesByProduct.values()), ibsCbsRatesByYear);
    }

    public FiscalCheckContext(UUID companyId, Map<String, ProductFiscalRule> rulesByProduct) {
        this(companyId, rulesByProduct, Map.of());
    }

    /** The rule for the item: its supplier's, or the code's general one. */
    public Optional<ProductFiscalRule> ruleFor(FiscalDocumentItem item) {
        return rules.forItem(item);
    }

    public Optional<IbsCbsRate> ibsCbsRateFor(int year) {
        return Optional.ofNullable(ibsCbsRatesByYear.get(year));
    }
}
