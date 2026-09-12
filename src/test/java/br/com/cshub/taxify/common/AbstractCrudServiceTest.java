package br.com.cshub.taxify.common;

import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.search.AbstractsSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractCrudServiceTest {

    private static class RecordingMapper extends AbstractMapper<String, String> {
        @Override
        protected String toEntity(String source) {
            return "entity-" + source;
        }

        @Override
        protected String toModel(String source) {
            return "model-" + source;
        }

        @Override
        public void merge(String source, String target) {
        }
    }

    // Must be public: SearchCriteriaUtil.buildCriteria reads record components via
    // reflection from a different package, which fails on a private nested record.
    public record TestFilter(String name, Boolean deleted) {
    }

    @Mock
    private AbstractJpaRepository<String, Long> repository;

    @Mock
    private AbstractCrudValidation<String, Long> validation;

    @Mock
    private AbstractsSpecification<String> specification;

    @Mock
    private MessageService messageService;

    @Mock
    private Specification<String> builtSpecification;

    private final RecordingMapper mapper = new RecordingMapper();

    private AbstractCrudService<String, Long, String, TestFilter> service;

    @BeforeEach
    void setUp() {
        service = new AbstractCrudService<>(repository, mapper, validation, messageService, specification) {
        };
    }

    @Test
    void findAllWithoutFilterUsesPlainPageableLookup() {
        Pageable pageable = PageRequest.of(0, 10);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of("a")));

        Page<String> result = service.findAll(pageable);

        assertThat(result.getContent()).containsExactly("model-a");
    }

    @Test
    void findAllWithFilterBuildsCriteriaAndDelegatesToSpecification() {
        Pageable pageable = PageRequest.of(0, 10);
        when(specification.listAllByCriteria(anyList())).thenReturn(builtSpecification);
        when(repository.findAll(builtSpecification, pageable)).thenReturn(new PageImpl<>(List.of("b")));

        Page<String> result = service.findAll(pageable, new TestFilter("acme", null));

        assertThat(result.getContent()).containsExactly("model-b");
    }

    @Test
    void findAllWithNullFilterFallsBackToPlainPageableLookup() {
        Pageable pageable = PageRequest.of(0, 10);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of("c")));

        Page<String> result = service.findAll(pageable, null);

        assertThat(result.getContent()).containsExactly("model-c");
    }

    @Test
    void createRunsFullValidationChainInOrderThenPersists() {
        // Note: create() maps the pre-save entity, not repository.save()'s return value
        // (relies on JPA mutating the passed-in entity in place, as Hibernate does).
        String result = service.create("dto");

        assertThat(result).isEqualTo("model-entity-dto");
        verify(repository).save("entity-dto");
        InOrder order = inOrder(validation);
        order.verify(validation).checkOwnerFieldsToCreate("entity-dto");
        order.verify(validation).checkMandatoryFields("entity-dto");
        order.verify(validation).checkRelations("entity-dto");
        order.verify(validation).checkValidationsExtras("entity-dto");
    }

    @Test
    void createSkipsValidationEntirelyWhenNoneIsConfigured() {
        AbstractCrudService<String, Long, String, TestFilter> noValidationService =
                new AbstractCrudService<>(repository, mapper, messageService, specification) {
                };

        String result = noValidationService.create("dto");

        assertThat(result).isEqualTo("model-entity-dto");
        verify(repository).save("entity-dto");
        verifyNoInteractions(validation);
    }

    @Test
    void updateResolvesConsistencyBeforeRunningRemainingChecks() {
        when(validation.checkUpdateConsistence(1L, "entity-dto")).thenReturn("consistent");

        String result = service.update(1L, "dto");

        assertThat(result).isEqualTo("model-consistent");
        verify(repository).save("consistent");
        verify(validation).checkMandatoryFields("consistent");
        verify(validation).checkRelations("consistent");
        verify(validation).checkValidationsExtras("consistent");
    }

    @Test
    void findByIdReturnsMappedModelWhenEntityExists() {
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.of("entity"));

        assertThat(service.findById(1L)).isEqualTo("model-entity");
    }

    @Test
    void findByIdThrowsResourceNotFoundWhenEntityMissing() {
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.empty());
        when(messageService.getMessage("error.entity.not.found")).thenReturn("not found");

        assertThatThrownBy(() -> service.findById(1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("not found");
    }

    @Test
    void deleteRemovesTheResolvedEntity() {
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.of("entity"));

        service.delete(1L);

        verify(repository).delete("entity");
    }

    @Test
    void setMessageNotFoundChangesTheNotFoundMessageKey() {
        service.setMessageNotFound("error.custom");
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.empty());
        when(messageService.getMessage("error.custom")).thenReturn("custom!");

        assertThatThrownBy(() -> service.findById(1L)).hasMessage("custom!");
    }
}
