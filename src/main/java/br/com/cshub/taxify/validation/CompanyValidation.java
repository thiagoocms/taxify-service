package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.CompanyMapper;
import br.com.cshub.taxify.repository.CompanyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class CompanyValidation extends AbstractCrudValidation<Company, UUID> {

    private final CompanyMapper mapper;
    private final CompanyRepository repository;
    private final DocumentValidation documentValidation;

    @Autowired
    public CompanyValidation(CompanyMapper mapper, CompanyRepository repository, MessageService messageService, DocumentValidation documentValidation) {
        super(repository, messageService);
        this.documentValidation = documentValidation;
        setMessageNotFound("error.Company.not.found");
        this.mapper = mapper;
        this.repository = repository;
    }

    @Override
    protected void checkOwnerFieldsToCreate(Company entity) {
        entity.setId(null);
        entity.setDeleted(false);
    }

    @Override
    protected Company checkUpdateConsistence(UUID id, Company toUpdateEntity) {
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

    @Override
    public Company checkRelations(Company toPersistEntity) {
        checkDocument(toPersistEntity);
        return toPersistEntity;
    }

    public Company checkDocument(Company toPersistEntity) {
        if (Objects.isNull(toPersistEntity.getDocument())) {
            return toPersistEntity;
        }

        if (Objects.isNull(toPersistEntity.getDocument().getId())) {
            this.documentValidation.checkExistByNumberAndType(toPersistEntity.getDocument());
            var document = this.documentValidation.saveRelation(toPersistEntity.getDocument());
            toPersistEntity.setDocument(document);
            return toPersistEntity;
        }

        var rel = this.documentValidation.checkExist(toPersistEntity.getDocument().getId());

        toPersistEntity.setDocument(rel);

        return toPersistEntity;
    }
}
