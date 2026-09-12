package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.search.SearchCriteria;
import br.com.cshub.taxify.domain.subscription.Subscription;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SubscriptionSpecification extends AbstractsSpecification<Subscription> {
    public SubscriptionSpecification(List<SearchCriteria> criterias) {
        super(criterias);
    }

    @Override
    protected void addExtrasPredicates(Root<Subscription> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {
        switch (criteria.getKey()) {
            case "companyId" -> {
                Join<Object, Object> companyJoin = root.join("company", JoinType.LEFT);
                predicates.add(builder.equal(companyJoin.get("id"), criteria.getValue()));
            }
            case "planId" -> {
                Join<Object, Object> planJoin = root.join("plan", JoinType.LEFT);
                predicates.add(builder.equal(planJoin.get("id"), criteria.getValue()));
            }
            default -> {
            }
        }
    }
}
