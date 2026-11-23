package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class CompanySpecification extends AbstractsSpecification<Company> {
    public CompanySpecification(List<SearchCriteria> criterias) {
        super(criterias);
    }

    @Override
    protected void addExtrasPredicates(Root<Company> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {
        if ("search".equals(criteria.getKey())) {
            predicates.add(searchPredicate(root, builder, criteria.getValue().toString()));
        }
    }

    /**
     * Name contains the term, OR - when the term has digits - the CNPJ/CPF contains them,
     * whether the document was stored with punctuation or not ("11.473.118", "11473118"...).
     */
    static Predicate searchPredicate(Root<Company> root, CriteriaBuilder builder, String term) {
        String text = term.strip().toLowerCase(Locale.ROOT);
        Predicate byName = builder.like(builder.lower(root.get("name")), "%" + text + "%");
        String digits = text.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return byName;
        }
        Expression<String> number = root.get("document").get("number");
        for (String punctuation : List.of(".", "/", "-", " ")) {
            number = builder.function("REPLACE", String.class, number, builder.literal(punctuation), builder.literal(""));
        }
        return builder.or(byName, builder.like(number, "%" + digits + "%"));
    }
}
