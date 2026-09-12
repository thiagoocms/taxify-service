package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AccountingFirmSpecification extends AbstractsSpecification<AccountingFirm> {
    public AccountingFirmSpecification(List<SearchCriteria> criterias) {
        super(criterias);
    }

    @Override
    protected void addExtrasPredicates(Root<AccountingFirm> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {

    }
}
