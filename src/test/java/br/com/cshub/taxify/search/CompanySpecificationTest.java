package br.com.cshub.taxify.search;

import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Company free search: name, or CNPJ digits whatever the punctuation. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "rawtypes"})
class CompanySpecificationTest {

    @Mock
    private Root<Company> root;
    @Mock
    private CriteriaBuilder builder;
    private final Predicate byName = mock(Predicate.class);
    private final Predicate byDocument = mock(Predicate.class);
    private final Predicate either = mock(Predicate.class);
    private final Expression<String> stripped = mock(Expression.class);

    @BeforeEach
    void setUp() {
        Path name = mock(Path.class);
        Path document = mock(Path.class);
        Path number = mock(Path.class);
        when(root.get("name")).thenReturn(name);
        when(root.get("document")).thenReturn(document);
        when(document.get("number")).thenReturn(number);
        Expression lowerName = mock(Expression.class);
        when(builder.lower(name)).thenReturn(lowerName);
        when(builder.like(eq(lowerName), anyString())).thenReturn(byName);
        when(builder.function(eq("REPLACE"), eq(String.class), any(), any(), any())).thenReturn((Expression) stripped);
        when(builder.like(eq(stripped), anyString())).thenReturn(byDocument);
        when(builder.or(byName, byDocument)).thenReturn(either);
    }

    @Test
    void textOnlySearchesTheName() {
        Predicate predicate = CompanySpecification.searchPredicate(root, builder, " Joelik ");

        assertThat(predicate).isSameAs(byName);
        verify(builder).like(any(Expression.class), eq("%joelik%"));
        verify(builder, never()).or(any(Predicate.class), any(Predicate.class));
    }

    @Test
    void digitsAlsoSearchTheDocumentWithoutPunctuation() {
        Predicate predicate = CompanySpecification.searchPredicate(root, builder, "11.473.118/0001");

        assertThat(predicate).isSameAs(either);
        verify(builder).like(stripped, "%114731180001%");
    }
}
