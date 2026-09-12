package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.search.SearchCriteria;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class AbstractsSpecificationTest {

    private static class RecordingSpecification extends AbstractsSpecification<Object> {
        final List<SearchCriteria> extrasCalls = new ArrayList<>();

        RecordingSpecification(List<SearchCriteria> criterias) {
            super(criterias);
        }

        @Override
        protected void addExtrasPredicates(Root<Object> root, CriteriaBuilder builder, List<Predicate> predicates, SearchCriteria criteria) {
            extrasCalls.add(criteria);
        }
    }

    @Mock
    private Root<Object> root;

    @Mock
    private CriteriaBuilder builder;

    @Mock
    private CriteriaQuery<?> query;

    private RecordingSpecification specification;

    @BeforeEach
    void setUp() {
        specification = new RecordingSpecification(List.of());
    }

    @Test
    void baseToPredicateReturnsAnEmptyConjunction() {
        Predicate empty = mock(Predicate.class);
        when(builder.and(any(Predicate[].class))).thenReturn(empty);

        Predicate result = specification.toPredicate(root, query, builder);

        assertThat(result).isEqualTo(empty);
        verify(builder).and(any(Predicate[].class));
    }

    @Test
    void nameCriteriaBuildsACaseInsensitiveLikePredicate() {
        Path path = mock(Path.class);
        Expression lowered = mock(Expression.class);
        Predicate likePredicate = mock(Predicate.class);
        Predicate finalPredicate = mock(Predicate.class);
        when(root.get("name")).thenReturn(path);
        when(builder.lower(path)).thenReturn(lowered);
        when(builder.like(lowered, "%acme%")).thenReturn(likePredicate);
        when(builder.and(any(Predicate[].class))).thenReturn(finalPredicate);

        var criteria = List.of(new SearchCriteria("name", "and", "ACME"));
        Predicate result = specification.listAllByCriteria(criteria).toPredicate(root, query, builder);

        assertThat(result).isEqualTo(finalPredicate);
        verify(builder).like(lowered, "%acme%");
    }

    @Test
    void deletedCriteriaBuildsAnEqualsPredicate() {
        Path path = mock(Path.class);
        Predicate equalsPredicate = mock(Predicate.class);
        when(root.get("deleted")).thenReturn(path);
        when(builder.equal(path, false)).thenReturn(equalsPredicate);
        when(builder.and(any(Predicate[].class))).thenReturn(equalsPredicate);

        var criteria = List.of(new SearchCriteria("deleted", "and", false));
        specification.listAllByCriteria(criteria).toPredicate(root, query, builder);

        verify(builder).equal(path, false);
    }

    @Test
    void userIdCriteriaJoinsAndAllowsNullOwner() {
        Join<Object, Object> join = mock(Join.class);
        Path path = mock(Path.class);
        Predicate equalsPredicate = mock(Predicate.class);
        Predicate isNullPredicate = mock(Predicate.class);
        Predicate orPredicate = mock(Predicate.class);
        when(root.join("user", JoinType.LEFT)).thenReturn(join);
        when(join.get("id")).thenReturn(path);
        when(builder.equal(path, "user-1")).thenReturn(equalsPredicate);
        when(builder.isNull(path)).thenReturn(isNullPredicate);
        when(builder.or(equalsPredicate, isNullPredicate)).thenReturn(orPredicate);
        when(builder.and(any(Predicate[].class))).thenReturn(orPredicate);

        var criteria = List.of(new SearchCriteria("userId", "and", "user-1"));
        specification.listAllByCriteria(criteria).toPredicate(root, query, builder);

        verify(builder).or(equalsPredicate, isNullPredicate);
    }

    @Test
    void unknownKeyDelegatesToAddExtrasPredicatesHook() {
        when(builder.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        var criteria = List.of(new SearchCriteria("companyId", "and", "company-1"));
        specification.listAllByCriteria(criteria).toPredicate(root, query, builder);

        assertThat(specification.extrasCalls).extracting(SearchCriteria::getKey).containsExactly("companyId");
    }

    @Test
    void orderByFieldsAreAppliedInTheRequestedDirection() {
        when(builder.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        Path path = mock(Path.class);
        when(root.get("name")).thenReturn(path);
        Order descOrder = mock(Order.class);
        when(builder.desc(path)).thenReturn(descOrder);

        var criteria = List.of(
                new SearchCriteria(List.of("[name]"), SearchCriteria.ORDER_BY_FIELDS_KEY),
                new SearchCriteria(SearchCriteria.ORDER_DESC)
        );
        specification.listAllByCriteria(criteria).toPredicate(root, query, builder);

        ArgumentCaptor<List<Order>> captor = ArgumentCaptor.forClass(List.class);
        verify(query).orderBy(captor.capture());
        assertThat(captor.getValue()).containsExactly(descOrder);
    }

    @Test
    void noOrderByCriteriaMeansOrderByIsNeverInvoked() {
        when(builder.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        specification.listAllByCriteria(List.of()).toPredicate(root, query, builder);

        verify(query, never()).orderBy(any(List.class));
    }
}
