package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.DocumentMapper;
import br.com.cshub.taxify.repository.DocumentRepository;
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
class DocumentValidationTest {

    @Mock
    private DocumentMapper mapper;
    @Mock
    private DocumentRepository repository;
    @Mock
    private MessageService messageService;

    private DocumentValidation validation;

    @BeforeEach
    void setUp() {
        validation = new DocumentValidation(mapper, repository, messageService);
    }

    @Test
    void checkOwnerFieldsToCreateResetsIdAndDeleted() {
        Document entity = new Document("12345678909", DocumentTypeEnum.CPF);
        entity.setId(UUID.randomUUID());
        entity.setDeleted(true);

        validation.checkOwnerFieldsToCreate(entity);

        assertThat(entity.getId()).isNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void checkUpdateConsistenceThrowsWhenIdsAreInconsistent() {
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");
        Document toUpdate = new Document();
        toUpdate.setId(UUID.randomUUID());

        assertThatThrownBy(() -> validation.checkUpdateConsistence(UUID.randomUUID(), toUpdate))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("bad request");
    }

    @Test
    void checkUpdateConsistenceMergesIntoPersistedEntity() {
        UUID id = UUID.randomUUID();
        Document toUpdate = new Document();
        toUpdate.setId(id);
        Document persisted = new Document();
        persisted.setId(id);
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));

        Document result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result).isSameAs(persisted);
        verify(mapper).merge(toUpdate, persisted);
    }

    @Test
    void checkExistByNumberAndTypeThrowsConflictWhenAlreadyExists() {
        Document entity = new Document("12345678909", DocumentTypeEnum.CPF);
        when(repository.existsByNumberAndTypeNative("12345678909", DocumentTypeEnum.CPF)).thenReturn(true);
        when(messageService.getMessage("error.document.exist")).thenReturn("already exists");

        assertThatThrownBy(() -> validation.checkExistByNumberAndType(entity))
                .isInstanceOf(ConflictException.class)
                .hasMessage("already exists");
    }

    @Test
    void checkExistByNumberAndTypePassesWhenNotFound() {
        Document entity = new Document("12345678909", DocumentTypeEnum.CPF);
        when(repository.existsByNumberAndTypeNative("12345678909", DocumentTypeEnum.CPF)).thenReturn(false);

        validation.checkExistByNumberAndType(entity);
    }

    @Test
    void saveRelationResetsOwnerFieldsAndPersists() {
        Document entity = new Document("12345678909", DocumentTypeEnum.CPF);
        entity.setId(UUID.randomUUID());
        entity.setDeleted(true);
        when(repository.save(entity)).thenReturn(entity);

        Document result = validation.saveRelation(entity);

        assertThat(result).isSameAs(entity);
        assertThat(entity.getId()).isNull();
        assertThat(entity.isDeleted()).isFalse();
        verify(repository).save(entity);
    }
}
