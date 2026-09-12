//package br.com.cshub.taxify.search;
//
//
//import com.thiagoocms.contaaqui.contaaqui_core.domain.search.SearchCriteria;
//import jakarta.persistence.criteria.*;
//import org.springframework.data.jpa.domain.Specification;
//
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Objects;
//
//public class ExampleSpecification implements Specification<Object> {
//
//    private final transient List<SearchCriteria> criterias;
//
//    public ExampleSpecification(List<SearchCriteria> criterias) {
//        this.criterias = criterias;
//    }
//
//    @Override
//    public Predicate toPredicate(Root<Object> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder) {
//        List<Predicate> predicates = new ArrayList<>();
//        return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
//    }
//
//    public static ExampleSpecification build(List<SearchCriteria> criterias) {
//        return new ExampleSpecification(criterias);
//    }
//
//    //**********************************************************************************
//    // PUBLIC STATIC FUNCTIONS
//    //**********************************************************************************
//
//    public static Specification<Object> listAllByCriteria(List<SearchCriteria> criterias) {
//
//        return (root, query, builder) -> {
//            List<Predicate> predicates = addPredicates(criterias, root, builder);
//            buildOrderBy(query, criterias, root, builder);
//            return SearchCriteriaUtil.andTogether(predicates, builder);
//        };
//    }
//
//    //**********************************************************************************
//    // PRIVATE FUNCTIONS
//    //**********************************************************************************
//
//    private static List<String> getOrderByFields(List<SearchCriteria> criterias) {
//        for (SearchCriteria criteria : criterias) {
//            if (criteria.getKey().equals(SearchCriteria.ORDER_BY_FIELDS_KEY) && criteria.getOrderByFields().size() > 0)
//                return criteria.getOrderByFields();
//        }
//        return null;
//    }
//
//    private static void buildOrderBy(CriteriaQuery<?> query, List<SearchCriteria> criterias,
//                                     Root<Object> root, CriteriaBuilder builder) {
//
//        String order = getOrder(criterias);
//        List<String> orderByOptions = cleanOptions(getOrderByFields(criterias));
//        List<Order> orders = new ArrayList<>();
//        for (String field : orderByOptions) {
//            orders.add(buildOrder(root, builder, order, field));
//        }
//        if (!orders.isEmpty()) {
//            query.orderBy(orders);
//        }
//    }
//
//    private static List<String> cleanOptions(List<String> orderByOptions) {
//        List<String> options = new ArrayList<>();
//        if (Objects.nonNull(orderByOptions)) {
//            for (String string : orderByOptions) {
//                String option = string.replace("[", "").replace("]", "");
//                if (!option.isEmpty()) {
//                    options.add(option);
//                }
//            }
//        }
//        return options;
//    }
//
//    private static Order buildOrder(Root<Object> root, CriteriaBuilder builder, String order, String field) {
//        switch (order) {
//            case SearchCriteria.ORDER_ASC:
//                return builder.asc(buildExpression(root, field));
//            case SearchCriteria.ORDER_DESC:
//                return builder.desc(buildExpression(root, field));
//        }
//        return builder.asc(buildExpression(root, field));
//    }
//
//    private static Expression<?> buildExpression(Root<Object> root, String field) {
//        return root.<String>get(field);
//    }
//
//    private static String getOrder(List<SearchCriteria> criterias) {
//        for (SearchCriteria criteria : criterias) {
//            if (criteria.getOrder() != null)
//                return criteria.getOrder();
//        }
//        return SearchCriteria.ORDER_ASC;
//    }
//
//    private static List<Predicate> addPredicates(List<SearchCriteria> criterias, Root<Object> root, CriteriaBuilder builder) {
//        List<Predicate> predicates = new ArrayList<>();
//        for (SearchCriteria criteria : criterias) {
//            addPredicate(root, builder, predicates, criteria);
//        }
//        return predicates;
//    }
//
//    private static void addPredicate(Root<Object> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {
//        String field = criteria.getKey();
//        if (field.equals("userId")) {
//            predicates.add(builder.equal(root.join("user", JoinType.INNER).get("id"), criteria.getValue()));
//        } else {
//            predicates.add(builder.equal(root.get(criteria.getKey()), criteria.getValue()));
//        }
//    }
//
//}
