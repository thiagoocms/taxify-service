package br.com.cshub.taxify.common;

import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.search.AbstractsSpecification;
import br.com.cshub.taxify.search.SearchCriteriaUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;

public abstract class AbstractCrudService<E, ID extends Serializable, M, F> implements Serializable {

    private String messageNotFound = "error.entity.not.found";

    protected final AbstractJpaRepository<E, ID> repository;
    protected final AbstractMapper<E, M> mapper;
    protected final AbstractCrudValidation<E, ID> validation;
    protected final MessageService messageService;
    protected final AbstractsSpecification<E> specification;

    protected AbstractCrudService(AbstractJpaRepository<E, ID> repository, AbstractMapper<E, M> mapper, AbstractCrudValidation<E, ID> validation, MessageService messageService, AbstractsSpecification<E> specification) {
        this.repository = repository;
        this.mapper = mapper;
        this.validation = validation;
        this.messageService = messageService;
        this.specification = specification;
    }

    protected AbstractCrudService(AbstractJpaRepository<E, ID> repository, AbstractMapper<E, M> mapper, MessageService messageService, AbstractsSpecification<E> specification) {
        this(repository, mapper, null, messageService, specification);
    }
    protected AbstractCrudService(AbstractJpaRepository<E, ID> repository, AbstractMapper<E, M> mapper, MessageService messageService) {
        this(repository, mapper, null, messageService, null);
    }
    protected AbstractCrudService(AbstractJpaRepository<E, ID> repository, AbstractMapper<E, M> mapper, AbstractCrudValidation<E, ID> validation, MessageService messageService) {
        this(repository, mapper, validation, messageService, null);
    }

    @Transactional(readOnly = true)
    public Page<M> findAll(Pageable pageable) {
        var page = repository.findAll(pageable);
        return mapper.toModels(page);
    }

    @Transactional(readOnly = true)
    public Page<M> findAll(Pageable pageable, F filter) {
        Specification<E> spec = null;
        if (specification != null && filter != null) {
            var criteria = SearchCriteriaUtil.buildCriteria(filter);
            spec = specification.listAllByCriteria(criteria);
        }
        spec = restrictQuery(spec);
        Page<E> page = spec != null
                ? this.repository.findAll(spec, pageable)
                : this.repository.findAll(pageable);
        return mapper.toModels(page);
    }

    /**
     * Extension point: subclasses whose entity belongs to an AccountingFirm (directly or
     * via a "company" association) override this to restrict the query to the current
     * user's own firm - see TenantAccessService. No-op by default.
     */
    protected Specification<E> restrictQuery(Specification<E> spec) {
        return spec;
    }

    /**
     * Extension point: subclasses override this to assert the current user may access a
     * single entity fetched by id (findCheckById/findById/update/delete) - see
     * TenantAccessService. No-op by default.
     */
    protected void checkAccess(E entity) {
    }

    @Transactional
    public M create(M model) {
        var entity = mapper.toEntity(model);
        if (validation != null) {
            validation.checkOwnerFieldsToCreate(entity);
            validation.checkMandatoryFields(entity);
            validation.checkRelations(entity);
            validation.checkValidationsExtras(entity);
        }
        repository.save(entity);    
        return mapper.toModel(entity);
    }

    @Transactional
    public M update(ID id, M model) {
        var entity = mapper.toEntity(model);
        if (validation != null) {
            entity = validation.checkUpdateConsistence(id, entity);
            validation.checkMandatoryFields(entity);
            validation.checkRelations(entity);
            validation.checkValidationsExtras(entity);
        }
        repository.save(entity);
        return mapper.toModel(entity);
    }

    @Transactional(readOnly = true)
    public M findById(ID id) {
        var entity = findCheckById(id);
        return mapper.toModel(entity);
    }

    @Transactional(readOnly = true)
    protected E findCheckById(ID id) {
        var entityOpt = repository.findFirstByIdAndDeletedIsFalse(id);
        var entity = entityOpt.orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage(messageNotFound)));
        checkAccess(entity);
        return entity;
    }

    @Transactional
    public void delete(ID id) {
        var entity = findCheckById(id);
        repository.delete(entity);
    }

    public void setMessageNotFound(String messageNotFound) {
        this.messageNotFound = messageNotFound;
    }

    protected M toModel(E entity) {
        return mapper.toModel(entity);
    }

}
