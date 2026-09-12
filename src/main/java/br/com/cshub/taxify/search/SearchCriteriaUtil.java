package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SearchCriteriaUtil {

    public static List<SearchCriteria> buildCriteria(Object filter) {

        List<SearchCriteria> searchCriteriaList = new ArrayList<>();

        if (filter == null) {
            return searchCriteriaList;
        }

        Class<?> clazz = filter.getClass();

        try {

            // ✅ SUPORTE A RECORD
            if (clazz.isRecord()) {

                for (var rc : clazz.getRecordComponents()) {

                    String property = rc.getName();

                    Object value = rc.getAccessor().invoke(filter);

                    if (isNullOrEmpty(value)) continue;

                    searchCriteriaList.add(new SearchCriteria(property, "and", value));
                }

            } else {

                // ✅ SUPORTE A CLASSE NORMAL (JavaBean)
//                PropertyDescriptor[] pds = PropertyUtils.getPropertyDescriptors(filter);
//
//                for (PropertyDescriptor pd : pds) {
//
//                    String property = pd.getName();
//
//                    if ("class".equals(property)) continue;
//
//                    var readMethod = pd.getReadMethod();
//                    if (readMethod == null) continue;
//
//                    Object value = readMethod.invoke(filter);
//
//                    if (isNullOrEmpty(value)) continue;
//
//                    searchCriteriaList.add(new SearchCriteria(property, "and", value));
//                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        addDeleted(searchCriteriaList);
        return searchCriteriaList;
    }

    public static Predicate andTogether(List<Predicate> predicates, CriteriaBuilder builder) {
        return builder.and(predicates.toArray(new Predicate[0]));
    }

    private static void addDeleted(List<SearchCriteria> criteria) {
        if (criteria.stream().anyMatch(item -> "active".equals(item.getKey()))) {
            var active = criteria.stream().filter(item -> "active".equals(item.getKey())).findFirst().orElseThrow();
            criteria.add(new SearchCriteria("deleted", "and", !(boolean) active.getValue()));
            criteria.remove(active);
        }

    }

    private static boolean isNullOrEmpty(Object value) {

        if (Objects.isNull(value)) {
            return true;
        }

        if (value instanceof String && ((String) value).trim().isEmpty()) {
            return true;
        }

        if (value instanceof List && ((List<?>) value).isEmpty()) {
            return true;
        }

        return false;
    }
}