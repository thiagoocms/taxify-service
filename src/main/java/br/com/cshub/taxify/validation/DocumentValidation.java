package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.DocumentMapper;
import br.com.cshub.taxify.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class DocumentValidation extends AbstractCrudValidation<Document, UUID> {

    private final DocumentMapper mapper;
    private final DocumentRepository repository;

    @Autowired
    public DocumentValidation(DocumentMapper mapper, DocumentRepository repository, MessageService messageService) {
        super(repository, messageService);
        setMessageNotFound("error.document.not.found");
        this.mapper = mapper;
        this.repository = repository;
    }

    @Override
    protected void checkOwnerFieldsToCreate(Document entity) {
        entity.setId(null);
        entity.setDeleted(false);
    }

    @Override
    protected Document checkUpdateConsistence(UUID id, Document toUpdateEntity) {
        if (Objects.isNull(id) || Objects.isNull(toUpdateEntity.getId())) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }

        if (id.compareTo(toUpdateEntity.getId()) != 0) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }

        var persistedEntity = checkExist(id);

        mapper.merge(toUpdateEntity, persistedEntity);

        return persistedEntity;
    }

    public void checkExistByNumberAndType(Document entity) {
            if (repository.existsByNumberAndTypeNative(entity.getNumber(), entity.getType())) {
                 throw new ConflictException(messageService.getMessage("error.document.exist"));
            }
    }

    public Document saveRelation(Document entity) {
        checkOwnerFieldsToCreate(entity);
        checkMandatoryFields(entity);
        checkRelations(entity);
        checkValidationsExtras(entity);
        return repository.save(entity);
    }
}
