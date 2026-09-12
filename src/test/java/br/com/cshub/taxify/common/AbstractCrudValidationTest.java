package br.com.cshub.taxify.common;

import br.com.cshub.taxify.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbstractCrudValidationTest {

    private static class RecordingValidation extends AbstractCrudValidation<String, Long> {
        boolean ownerFieldsChecked;
        boolean mandatoryChecked;
        boolean relationsChecked;
        boolean extrasChecked;

        RecordingValidation(AbstractJpaRepository<String, Long> repository, MessageService messageService) {
            super(repository, messageService);
        }

        @Override
        protected void checkOwnerFieldsToCreate(String entity) {
            ownerFieldsChecked = true;
        }

        @Override
        protected String checkUpdateConsistence(Long id, String toUpdateEntity) {
            return toUpdateEntity;
        }

        @Override
        protected void checkMandatoryFields(String entity) {
            mandatoryChecked = true;
        }

        @Override
        public String checkRelations(String toPersistEntity) {
            relationsChecked = true;
            return toPersistEntity;
        }

        @Override
        protected void checkValidationsExtras(String entity) {
            extrasChecked = true;
        }
    }

    @Mock
    private AbstractJpaRepository<String, Long> repository;

    @Mock
    private MessageService messageService;

    private RecordingValidation validation;

    @BeforeEach
    void setUp() {
        validation = new RecordingValidation(repository, messageService);
    }

    @Test
    void checkExistReturnsEntityWhenFound() {
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.of("entity"));

        assertThat(validation.checkExist(1L)).isEqualTo("entity");
    }

    @Test
    void checkExistThrowsResourceNotFoundWithDefaultMessageWhenMissing() {
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.empty());
        when(messageService.getMessage("error.entity.not.found")).thenReturn("Registro não encontrado.");

        assertThatThrownBy(() -> validation.checkExist(1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Registro não encontrado.");
    }

    @Test
    void checkExistUsesCustomMessageNotFoundWhenConfigured() {
        validation.setMessageNotFound("error.custom.not.found");
        when(repository.findFirstByIdAndDeletedIsFalse(1L)).thenReturn(Optional.empty());
        when(messageService.getMessage("error.custom.not.found")).thenReturn("custom message");

        assertThatThrownBy(() -> validation.checkExist(1L)).hasMessage("custom message");
    }

    @Test
    void saveRelationRunsFullValidationChainInOrderAndPersists() {
        when(repository.save("entity")).thenReturn("saved");

        String result = validation.saveRelation("entity");

        assertThat(result).isEqualTo("saved");
        assertThat(validation.ownerFieldsChecked).isTrue();
        assertThat(validation.mandatoryChecked).isTrue();
        assertThat(validation.relationsChecked).isTrue();
        assertThat(validation.extrasChecked).isTrue();
    }

    @Test
    void checkRelationsDefaultsToIdentityWhenNotOverridden() {
        AbstractCrudValidation<String, Long> plain = new AbstractCrudValidation<>(repository, messageService) {
            @Override
            protected void checkOwnerFieldsToCreate(String entity) {
            }

            @Override
            protected String checkUpdateConsistence(Long id, String toUpdateEntity) {
                return toUpdateEntity;
            }
        };

        assertThat(plain.checkRelations("unchanged")).isEqualTo("unchanged");
    }
}
