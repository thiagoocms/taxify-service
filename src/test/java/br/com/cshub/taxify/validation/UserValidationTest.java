package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.UserMapper;
import br.com.cshub.taxify.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserValidationTest {

    @Mock
    private UserMapper mapper;
    @Mock
    private UserRepository repository;
    @Mock
    private MessageService messageService;
    @Mock
    private DocumentValidation documentValidation;
    @Mock
    private PasswordEncoder passwordEncoder;

    private UserValidation validation;

    @BeforeEach
    void setUp() {
        validation = new UserValidation(mapper, repository, messageService, documentValidation, passwordEncoder);
    }

    @Test
    void checkOwnerFieldsToCreateResetsIdAndDeletedAndEncodesPassword() {
        User entity = new User(UUID.randomUUID());
        entity.setDeleted(true);
        entity.setPassword("plain");
        when(passwordEncoder.encode("plain")).thenReturn("encoded");

        validation.checkOwnerFieldsToCreate(entity);

        assertThat(entity.getId()).isNull();
        assertThat(entity.isDeleted()).isFalse();
        assertThat(entity.getPassword()).isEqualTo("encoded");
    }

    @Test
    void checkUpdateConsistenceThrowsWhenIdsAreInconsistent() {
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");
        User toUpdate = new User(UUID.randomUUID());

        assertThatThrownBy(() -> validation.checkUpdateConsistence(UUID.randomUUID(), toUpdate))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("bad request");
    }

    @Test
    void checkUpdateConsistenceMergesIntoPersistedEntity() {
        UUID id = UUID.randomUUID();
        User toUpdate = new User(id);
        User persisted = new User(id);
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));

        User result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result).isSameAs(persisted);
        verify(mapper).merge(toUpdate, persisted);
    }

    @Test
    void checkDocumentCreatesANewDocumentWhenItHasNoId() {
        User entity = new User();
        Document newDocument = new Document("12345678909", DocumentTypeEnum.CPF);
        entity.setDocument(newDocument);
        Document saved = new Document("12345678909", DocumentTypeEnum.CPF);
        saved.setId(UUID.randomUUID());
        when(documentValidation.saveRelation(newDocument)).thenReturn(saved);

        validation.checkDocument(entity);

        verify(documentValidation).checkExistByNumberAndType(newDocument);
        assertThat(entity.getDocument()).isSameAs(saved);
    }

    @Test
    void checkDocumentResolvesAnExistingDocumentById() {
        User entity = new User();
        UUID documentId = UUID.randomUUID();
        Document reference = new Document();
        reference.setId(documentId);
        entity.setDocument(reference);
        Document resolved = new Document("12345678909", DocumentTypeEnum.CPF);
        resolved.setId(documentId);
        when(documentValidation.checkExist(documentId)).thenReturn(resolved);

        validation.checkDocument(entity);

        assertThat(entity.getDocument()).isSameAs(resolved);
    }

    @Test
    void checkValidationsExtrasThrowsWhenLoginAlreadyUsedByAnotherUser() {
        User entity = new User(UUID.randomUUID());
        entity.setLogin("jane");
        when(repository.countByLoginAndDeletedIsFalse(entity.getId(), "jane")).thenReturn(1L);
        when(messageService.getMessage("error.user.login.already.exists")).thenReturn("login already exists");

        assertThatThrownBy(() -> validation.checkValidationsExtras(entity))
                .isInstanceOf(ConflictException.class)
                .hasMessage("login already exists");
    }

    @Test
    void checkValidationsExtrasPassesWhenLoginIsFree() {
        User entity = new User(UUID.randomUUID());
        entity.setLogin("jane");
        when(repository.countByLoginAndDeletedIsFalse(entity.getId(), "jane")).thenReturn(0L);

        validation.checkValidationsExtras(entity);
    }
}
