package br.com.cshub.taxify.common;

import br.com.cshub.taxify.exception.ResourceNotFoundException;

import java.io.Serializable;

public abstract class AbstractCrudValidation<E, ID extends Serializable> {

    private String messageNotFound = "error.entity.not.found";

    protected final AbstractJpaRepository<E, ID> repository;
    protected final MessageService messageService;

    protected AbstractCrudValidation(AbstractJpaRepository<E, ID> repository, MessageService messageService) {
        this.repository = repository;
        this.messageService = messageService;
    }

    protected abstract void checkOwnerFieldsToCreate(E entity);

    protected abstract E checkUpdateConsistence(ID id, E toUpdateEntity);

    protected void checkMandatoryFields(E entity) {
    }

    public E checkRelations(E toPersistEntity) {
        return toPersistEntity;
    }

    public E checkExist(ID id) {
        var entityOpt = repository.findFirstByIdAndDeletedIsFalse(id);
        return entityOpt.orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage(messageNotFound)));
    }

    public void setMessageNotFound(String messageNotFound) {
        this.messageNotFound = messageNotFound;
    }

    protected void checkValidationsExtras(E entity) {
    }

    public E saveRelation(E entity) {
        checkOwnerFieldsToCreate(entity);
        checkMandatoryFields(entity);
        checkRelations(entity);
        checkValidationsExtras(entity);
        return repository.save(entity);
    }
}
