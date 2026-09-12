package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductFiscalRuleSpecification extends AbstractsSpecification<ProductFiscalRule> {
    public ProductFiscalRuleSpecification(List<SearchCriteria> criterias) {
        super(criterias);
    }

    @Override
    protected void addExtrasPredicates(Root<ProductFiscalRule> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {
        switch (criteria.getKey()) {
            case "companyId" -> {
                Join<Object, Object> companyJoin = root.join("company", JoinType.LEFT);
                predicates.add(builder.equal(companyJoin.get("id"), criteria.getValue()));
            }
            case "codigoProduto" -> predicates.add(builder.equal(root.get("codigoProduto"), criteria.getValue()));
            default -> {
            }
        }
    }
}
