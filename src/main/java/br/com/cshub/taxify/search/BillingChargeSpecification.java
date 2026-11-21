package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BillingChargeSpecification extends AbstractsSpecification<BillingCharge> {

    public BillingChargeSpecification(List<SearchCriteria> criterias) {
        super(criterias);
    }

    @Override
    protected void addExtrasPredicates(Root<BillingCharge> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {
        switch (criteria.getKey()) {
            case "companyId" -> predicates.add(builder.equal(root.get("company").get("id"), criteria.getValue()));
            case "status" -> predicates.add(builder.equal(root.get("status").as(String.class), String.valueOf(criteria.getValue())));
            case "source" -> predicates.add(builder.equal(root.get("source").as(String.class), String.valueOf(criteria.getValue())));
            default -> {
            }
        }
    }
}
