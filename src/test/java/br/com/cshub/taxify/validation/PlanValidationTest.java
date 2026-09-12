package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.PlanMapper;
import br.com.cshub.taxify.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanValidationTest {

    @Mock
    private PlanMapper mapper;
    @Mock
    private PlanRepository repository;
    @Mock
    private MessageService messageService;

    private PlanValidation validation;

    @BeforeEach
    void setUp() {
        validation = new PlanValidation(mapper, repository, messageService);
    }

    @Test
    void checkOwnerFieldsToCreateResetsIdAndDeleted() {
        Plan entity = new Plan(UUID.randomUUID());
        entity.setDeleted(true);

        validation.checkOwnerFieldsToCreate(entity);

        assertThat(entity.getId()).isNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void checkUpdateConsistenceThrowsWhenPathIdIsNull() {
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");
        Plan toUpdate = new Plan(UUID.randomUUID());

        assertThatThrownBy(() -> validation.checkUpdateConsistence(null, toUpdate))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("bad request");
    }

    @Test
    void checkUpdateConsistenceThrowsWhenIdsDiffer() {
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");
        Plan toUpdate = new Plan(UUID.randomUUID());

        assertThatThrownBy(() -> validation.checkUpdateConsistence(UUID.randomUUID(), toUpdate))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void checkUpdateConsistenceMergesIntoPersistedEntity() {
        UUID id = UUID.randomUUID();
        Plan toUpdate = new Plan(id);
        Plan persisted = new Plan(id);
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));

        Plan result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result).isSameAs(persisted);
        verify(mapper).merge(toUpdate, persisted);
    }

    @Test
    void checkRelationsDefaultsToIdentity() {
        Plan entity = new Plan(UUID.randomUUID());

        assertThat(validation.checkRelations(entity)).isSameAs(entity);
    }
}
