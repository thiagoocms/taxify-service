package br.com.cshub.taxify.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.core.PropertyPath;
import org.springframework.data.core.PropertyReferenceException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code sort=} of a CRUD list, checked against the entity: a property renamed in Java (English naming,
 * wave 1) is still accepted by its old name, and a property that doesn't exist is reported instead of failing
 * deep inside the query. Pure: no I/O.
 */
public final class SortProperties {

    private SortProperties() {
    }

    /**
     * The pageable with every sort property translated from its legacy name and checked on {@code entity}.
     *
     * @return empty when a property (after the translation) doesn't exist on the entity
     */
    public static Optional<Pageable> resolve(Pageable pageable, Class<?> entity, Map<String, String> legacyNames) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return Optional.ofNullable(pageable);
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            String property = legacyNames.getOrDefault(order.getProperty(), order.getProperty());
            if (!exists(property, entity)) {
                return Optional.empty();
            }
            orders.add(order.withProperty(property));
        }
        Sort sort = Sort.by(orders);
        return Optional.of(pageable.isPaged() ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort)
                : Pageable.unpaged(sort));
    }

    private static boolean exists(String property, Class<?> entity) {
        try {
            PropertyPath.from(property, entity);
            return true;
        } catch (PropertyReferenceException | IllegalArgumentException e) {
            return false;
        }
    }
}
