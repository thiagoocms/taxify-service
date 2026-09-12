package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.CompanyMapper;
import br.com.cshub.taxify.repository.CompanyRepository;
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
class CompanyValidationTest {

    @Mock
    private CompanyMapper mapper;
    @Mock
    private CompanyRepository repository;
    @Mock
    private MessageService messageService;
    @Mock
    private DocumentValidation documentValidation;

    private CompanyValidation validation;

    @BeforeEach
    void setUp() {
        validation = new CompanyValidation(mapper, repository, messageService, documentValidation);
    }

    @Test
    void checkOwnerFieldsToCreateResetsIdAndDeleted() {
        Company entity = new Company(UUID.randomUUID());
        entity.setDeleted(true);

        validation.checkOwnerFieldsToCreate(entity);

        assertThat(entity.getId()).isNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void checkUpdateConsistenceThrowsWhenPathIdIsNull() {
        Company toUpdate = new Company(UUID.randomUUID());
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");

        assertThatThrownBy(() -> validation.checkUpdateConsistence(null, toUpdate))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("bad request");
    }

    @Test
    void checkUpdateConsistenceThrowsWhenEntityIdIsNull() {
        Company toUpdate = new Company();
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");

        assertThatThrownBy(() -> validation.checkUpdateConsistence(UUID.randomUUID(), toUpdate))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void checkUpdateConsistenceThrowsWhenPathIdDiffersFromEntityId() {
        Company toUpdate = new Company(UUID.randomUUID());
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");

        assertThatThrownBy(() -> validation.checkUpdateConsistence(UUID.randomUUID(), toUpdate))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void checkUpdateConsistenceMergesIntoThePersistedEntity() {
        UUID id = UUID.randomUUID();
        Company toUpdate = new Company(id);
        Company persisted = new Company(id);
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));

        Company result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result).isSameAs(persisted);
        verify(mapper).merge(toUpdate, persisted);
    }

    @Test
    void checkDocumentDoesNothingWhenNoDocumentIsSet() {
        Company entity = new Company();

        Company result = validation.checkDocument(entity);

        assertThat(result.getDocument()).isNull();
    }

    @Test
    void checkDocumentCreatesANewDocumentWhenItHasNoId() {
        Company entity = new Company();
        Document newDocument = new Document("12345678000199", DocumentTypeEnum.CNPJ);
        entity.setDocument(newDocument);
        Document savedDocument = new Document("12345678000199", DocumentTypeEnum.CNPJ);
        savedDocument.setId(UUID.randomUUID());
        when(documentValidation.saveRelation(newDocument)).thenReturn(savedDocument);

        validation.checkDocument(entity);

        verify(documentValidation).checkExistByNumberAndType(newDocument);
        verify(documentValidation).saveRelation(newDocument);
        assertThat(entity.getDocument()).isSameAs(savedDocument);
    }

    @Test
    void checkDocumentResolvesAnExistingDocumentById() {
        Company entity = new Company();
        UUID documentId = UUID.randomUUID();
        Document reference = new Document();
        reference.setId(documentId);
        entity.setDocument(reference);
        Document resolved = new Document("12345678000199", DocumentTypeEnum.CNPJ);
        resolved.setId(documentId);
        when(documentValidation.checkExist(documentId)).thenReturn(resolved);

        validation.checkDocument(entity);

        assertThat(entity.getDocument()).isSameAs(resolved);
    }
}
