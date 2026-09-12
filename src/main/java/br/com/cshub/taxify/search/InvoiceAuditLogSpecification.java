package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class InvoiceAuditLogSpecification extends AbstractsSpecification<InvoiceAuditLog> {
    public InvoiceAuditLogSpecification(List<SearchCriteria> criterias) {
        super(criterias);
    }

    @Override
    protected void addExtrasPredicates(Root<InvoiceAuditLog> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {
        String field = criteria.getKey();

        switch (field) {
            case "companyId" -> {
                Join<Object, Object> userJoin = root.join("company", JoinType.LEFT);

                predicates.add(
                        builder.or(
                                builder.equal(userJoin.get("id"), criteria.getValue()),
                                builder.isNull(userJoin.get("id"))
                        )
                );
            }
            default -> predicates.add(builder.equal(root.get(criteria.getKey()), criteria.getValue()));
        }

    }
}
