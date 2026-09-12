package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.search.AbstractsSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractCrudServiceTest {

    private static class Widget extends AbstractAuditingEntity {
        private final String value;

        Widget(String value) {
            this.value = value;
        }

        String getValue() {
            return value;
        }
    }

    private static class RecordingMapper extends AbstractMapper<Widget, String> {
        @Override
        protected Widget toEntity(String source) {
            return new Widget("entity-" + source);
        }

        @Override
        protected String toModel(Widget source) {
            return "model-" + source.getValue();
        }

        @Override
        public void merge(Widget source, Widget target) {
        }
    }

    // Must be public: SearchCriteriaUtil.buildCriteria reads record components via
    // reflection from a different package, which fails on a private nested record.
    public record TestFilter(String name, Boolean deleted) {
    }

    @Mock
    private AbstractJpaRepository<Widget, Long> repository;

    @Mock
    private AbstractCrudValidation<Widget, Long> validation;

    @Mock
    private AbstractsSpecification<Widget> specification;

    @Mock
    private MessageService messageService;

    @Mock
    private Specification<Widget> builtSpecification;

    private final RecordingMapper mapper = new RecordingMapper();

    private AbstractCrudService<Widget, Long, String, TestFilter> service;

    @BeforeEach
    void setUp() {
        service = new AbstractCrudService<>(repository, mapper, validation, messageService, specification) {
        };
    }

    @Test
    void findAllWithoutFilterUsesPlainPageableLookup() {
        Pageable pageable = PageRequest.of(0, 10);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(new Widget("a"))));

        Page<String> result = service.findAll(pageable);

        assertThat(result.getContent()).containsExactly("model-a");
    }

    @Test
    void findAllWithFilterBuildsCriteriaAndDelegatesToSpecification() {
        Pageable pageable = PageRequest.of(0, 10);
        when(specification.listAllByCriteria(anyList())).thenReturn(builtSpecification);
        when(repository.findAll(builtSpecification, pageable)).thenReturn(new PageImpl<>(List.of(new Widget("b"))));

        Page<String> result = service.findAll(pageable, new TestFilter("acme", null));

        assertThat(result.getContent()).containsExactly("model-b");
    }

    @Test
    void findAllWithNullFilterFallsBackToPlainPageableLookup() {
        Pageable pageable = PageRequest.of(0, 10);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(new Widget("c"))));

        Page<String> result = service.findAll(pageable, null);

        assertThat(result.getContent()).containsExactly("model-c");
    }

    @Test
    void createRunsFullValidationChainInOrderThenPersists() {
        // Note: create() maps the pre-save entity, not repository.save()'s return value
        // (relies on JPA mutating the passed-in entity in place, as Hibernate does).
        String result = service.create("dto");

        assertThat(result).isEqualTo("model-entity-dto");
        ArgumentCaptor<Widget> saved = ArgumentCaptor.forClass(Widget.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getValue()).isEqualTo("entity-dto");
        InOrder order = inOrder(validation);
        order.verify(validation).checkOwnerFieldsToCreate(saved.getValue());
        order.verify(validation).checkMandatoryFields(saved.getValue());
        order.verify(validation).checkRelations(saved.getValue());
        order.verify(validation).checkValidationsExtras(saved.getValue());
    }

    @Test
    void createSkipsValidationEntirelyWhenNoneIsConfigured() {
        AbstractCrudService<Widget, Long, String, TestFilter> noValidationService =
                new AbstractCrudService<>(repository, mapper, messageService, specification) {
                };

        String result = noValidationService.create("dto");

        assertThat(result).isEqualTo("model-entity-dto");
        ArgumentCaptor<Widget> saved = ArgumentCaptor.forClass(Widget.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getValue()).isEqualTo("entity-dto");
        verifyNoInteractions(validation);
    }

    @Test
    void updateResolvesConsistencyBeforeRunningRemainingChecks() {
        Widget consistent = new Widget("consistent");
        when(validation.checkUpdateConsistence(eq(1L), any())).thenReturn(consistent);

        String result = service.update(1L, "dto");

        assertThat(result).isEqualTo("model-consistent");
        verify(repository).save(consistent);
        verify(validation).checkMandatoryFields(consistent);
        verify(validation).checkRelations(consistent);
        verify(validation).checkValidationsExtras(consistent);
    }

    @Test
    void findByIdReturnsMappedModelWhenEntityExists() {
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.of(new Widget("entity")));

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
    void deleteSoftDeletesTheResolvedEntityInsteadOfRemovingIt() {
        Widget entity = new Widget("entity");
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.of(entity));

        service.delete(1L);

        assertThat(entity.isDeleted()).isTrue();
        verify(repository).save(entity);
    }

    @Test
    void setMessageNotFoundChangesTheNotFoundMessageKey() {
        service.setMessageNotFound("error.custom");
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.empty());
        when(messageService.getMessage("error.custom")).thenReturn("custom!");

        assertThatThrownBy(() -> service.findById(1L)).hasMessage("custom!");
    }
}
