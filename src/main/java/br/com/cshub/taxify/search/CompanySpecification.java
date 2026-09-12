package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CompanySpecification extends AbstractsSpecification<Company> {
    public CompanySpecification(List<SearchCriteria> criterias) {
        super(criterias);
    }

    @Override
    protected void addExtrasPredicates(Root<Company> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {

    }
}
