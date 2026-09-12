package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.search.SearchCriteria;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UserCompanySpecification extends AbstractsSpecification<UserCompany> {
    public UserCompanySpecification(List<SearchCriteria> criterias) {
        super(criterias);
    }

    @Override
    protected void addExtrasPredicates(Root<UserCompany> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {

    }
}
