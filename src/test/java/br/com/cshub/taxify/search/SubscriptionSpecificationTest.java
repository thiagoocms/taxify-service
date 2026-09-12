package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.search.SearchCriteria;
import br.com.cshub.taxify.domain.subscription.Subscription;
import jakarta.persistence.criteria.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class SubscriptionSpecificationTest {

    @Mock
    private Root<Subscription> root;
    @Mock
    private CriteriaBuilder builder;

    private final SubscriptionSpecification specification = new SubscriptionSpecification(List.of());

    @Test
    void companyIdCriteriaJoinsCompanyAndFiltersById() {
        Join<Object, Object> companyJoin = mock(Join.class);
        Path path = mock(Path.class);
        Predicate equalsPredicate = mock(Predicate.class);
        when(root.join("company", JoinType.LEFT)).thenReturn(companyJoin);
        when(companyJoin.get("id")).thenReturn(path);
        when(builder.equal(path, "company-1")).thenReturn(equalsPredicate);

        List<Predicate> predicates = new ArrayList<>();
        specification.addExtrasPredicates(root, builder, predicates, new SearchCriteria("companyId", "and", "company-1"));

        verify(builder).equal(path, "company-1");
        org.assertj.core.api.Assertions.assertThat(predicates).containsExactly(equalsPredicate);
    }

    @Test
    void planIdCriteriaJoinsPlanAndFiltersById() {
        Join<Object, Object> planJoin = mock(Join.class);
        Path path = mock(Path.class);
        Predicate equalsPredicate = mock(Predicate.class);
        when(root.join("plan", JoinType.LEFT)).thenReturn(planJoin);
        when(planJoin.get("id")).thenReturn(path);
        when(builder.equal(path, "plan-1")).thenReturn(equalsPredicate);

        List<Predicate> predicates = new ArrayList<>();
        specification.addExtrasPredicates(root, builder, predicates, new SearchCriteria("planId", "and", "plan-1"));

        verify(builder).equal(path, "plan-1");
        org.assertj.core.api.Assertions.assertThat(predicates).containsExactly(equalsPredicate);
    }

    @Test
    void unrelatedCriteriaKeyAddsNoPredicate() {
        List<Predicate> predicates = new ArrayList<>();

        specification.addExtrasPredicates(root, builder, predicates, new SearchCriteria("somethingElse", "and", "value"));

        org.assertj.core.api.Assertions.assertThat(predicates).isEmpty();
        verifyNoInteractions(builder);
    }
}
