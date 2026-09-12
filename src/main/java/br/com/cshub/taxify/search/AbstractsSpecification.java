package br.com.cshub.taxify.search;


import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public abstract class AbstractsSpecification<E> implements Specification<E> {

    private final transient List<SearchCriteria> criterias;

    public AbstractsSpecification(List<SearchCriteria> criterias) {
        this.criterias = criterias;
    }

    @Override
    public Predicate toPredicate(Root<E> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder) {
        List<Predicate> predicates = new ArrayList<>();
        return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
    }

    public Specification<E> listAllByCriteria(List<SearchCriteria> criterias) {

        return (root, query, builder) -> {
            List<Predicate> predicates = addPredicates(criterias, root, builder);
            buildOrderBy(query, criterias, root, builder);
            return SearchCriteriaUtil.andTogether(predicates, builder);
        };
    }

    //**********************************************************************************
    // PRIVATE FUNCTIONS
    //**********************************************************************************

    private List<String> getOrderByFields(List<SearchCriteria> criterias) {
        for (SearchCriteria criteria : criterias) {
            if (criteria.getKey().equals(SearchCriteria.ORDER_BY_FIELDS_KEY) && !criteria.getOrderByFields().isEmpty())
                return criteria.getOrderByFields();
        }
        return null;
    }

    private void buildOrderBy(CriteriaQuery<?> query, List<SearchCriteria> criterias,
                                     Root<E> root, CriteriaBuilder builder) {

        String order = getOrder(criterias);
        List<String> orderByOptions = cleanOptions(getOrderByFields(criterias));
        List<Order> orders = new ArrayList<>();
        for (String field : orderByOptions) {
            orders.add(buildOrder(root, builder, order, field));
        }
        if (!orders.isEmpty()) {
            query.orderBy(orders);
        }
    }

    private List<String> cleanOptions(List<String> orderByOptions) {
        List<String> options = new ArrayList<>();
        if (Objects.nonNull(orderByOptions)) {
            for (String string : orderByOptions) {
                String option = string.replace("[", "").replace("]", "");
                if (!option.isEmpty()) {
                    options.add(option);
                }
            }
        }
        return options;
    }

    private Order buildOrder(Root<E> root, CriteriaBuilder builder, String order, String field) {
        return switch (order) {
            case SearchCriteria.ORDER_DESC -> builder.desc(buildExpression(root, field));
            default -> builder.asc(buildExpression(root, field));
        };
    }

    private Expression<?> buildExpression(Root<E> root, String field) {
        return root.<String>get(field);
    }

    private String getOrder(List<SearchCriteria> criterias) {
        for (SearchCriteria criteria : criterias) {
            if (criteria.getOrder() != null)
                return criteria.getOrder();
        }
        return SearchCriteria.ORDER_ASC;
    }

    private List<Predicate> addPredicates(List<SearchCriteria> criterias, Root<E> root, CriteriaBuilder builder) {
        List<Predicate> predicates = new ArrayList<>();
        for (SearchCriteria criteria : criterias) {
            addPredicate(root, builder, predicates, criteria);
        }
        return predicates;
    }

    private void addPredicate(Root<E> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {
        String field = criteria.getKey();
        switch (field) {
            case "userId" -> {
                Join<Object, Object> userJoin = root.join("user", JoinType.LEFT);

                predicates.add(
                        builder.or(
                                builder.equal(userJoin.get("id"), criteria.getValue()),
                                builder.isNull(userJoin.get("id"))
                        )
                );
            }
            case "name" -> {
                String value = criteria.getValue().toString().toLowerCase(Locale.ROOT);

                predicates.add(
                        builder.like(
                                builder.lower(root.get("name")),
                                "%" + value + "%"
                        )
                );
            }            case "deleted" -> predicates.add(builder.equal(root.get(criteria.getKey()), criteria.getValue()));
        }

//        else {
//            predicates.add(builder.equal(root.get(criteria.getKey()), criteria.getValue()));
//        }
        addExtrasPredicates(root, builder, predicates, criteria);
    }

    protected abstract void addExtrasPredicates(Root<E> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria);
}
