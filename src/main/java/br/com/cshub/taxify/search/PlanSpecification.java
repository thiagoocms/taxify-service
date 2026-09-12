package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlanSpecification extends AbstractsSpecification<Plan> {
    public PlanSpecification(List<SearchCriteria> criterias) {
        super(criterias);
    }

    @Override
    protected void addExtrasPredicates(Root<Plan> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {

    }
}
