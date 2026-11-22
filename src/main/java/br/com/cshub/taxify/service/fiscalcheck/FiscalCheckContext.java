package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.reform.IbsCbsRate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the checks of one company share, loaded once per run instead of once per item:
 * the company's product rules by product code and the IBS/CBS reference rates by year.
 */
public record FiscalCheckContext(UUID companyId, Map<String, ProductFiscalRule> rulesByProduct,
                                 Map<Integer, IbsCbsRate> ibsCbsRatesByYear) {

    public FiscalCheckContext {
        rulesByProduct = Map.copyOf(rulesByProduct);
        ibsCbsRatesByYear = Map.copyOf(ibsCbsRatesByYear);
    }

    public FiscalCheckContext(UUID companyId, Map<String, ProductFiscalRule> rulesByProduct) {
        this(companyId, rulesByProduct, Map.of());
    }

    public Optional<ProductFiscalRule> ruleFor(String codigoProduto) {
        return codigoProduto == null ? Optional.empty() : Optional.ofNullable(rulesByProduct.get(codigoProduto));
    }

    public Optional<IbsCbsRate> ibsCbsRateFor(int year) {
        return Optional.ofNullable(ibsCbsRatesByYear.get(year));
    }
}
