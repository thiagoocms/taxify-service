package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.SortProperties;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /product-fiscal-rules?sort=codigoProduto} keeps working after the entity's fields went to English. */
class ProductFiscalRuleLegacySortTest {

    private static Optional<Pageable> sortBy(String property) {
        return SortProperties.resolve(PageRequest.of(0, 20, Sort.by(property)), ProductFiscalRule.class,
                ProductFiscalRuleService.LEGACY_SORT_PROPERTIES);
    }

    @Test
    void everyOldNameMapsToAnExistingProperty() {
        for (Map.Entry<String, String> legacy : ProductFiscalRuleService.LEGACY_SORT_PROPERTIES.entrySet()) {
            Optional<Pageable> resolved = sortBy(legacy.getKey());
            assertThat(resolved).as(legacy.getKey()).isPresent();
            assertThat(resolved.get().getSort().getOrderFor(legacy.getValue())).as(legacy.getKey()).isNotNull();
        }
    }

    @Test
    void theNewNamesAndTheUnchangedOnesStillSort() {
        assertThat(sortBy("productCode")).isPresent();
        assertThat(sortBy("createdDate")).isPresent();
    }

    @Test
    void anUnknownPropertyIsRejected() {
        assertThat(sortBy("naoExiste")).isEmpty();
    }
}
