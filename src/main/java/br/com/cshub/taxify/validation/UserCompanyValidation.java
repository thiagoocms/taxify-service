package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.domain.user_company.UserCompanyId;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.UserCompanyMapper;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class UserCompanyValidation extends AbstractCrudValidation<UserCompany, UserCompanyId> {

    private final UserCompanyMapper mapper;
    private final UserValidation userValidation;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;

    @Autowired
    public UserCompanyValidation(UserCompanyMapper mapper, UserCompanyRepository repository, MessageService messageService, UserValidation userValidation, CompanyValidation companyValidation, TenantAccessService tenantAccessService) {
        super(repository, messageService);
        this.userValidation = userValidation;
        this.companyValidation = companyValidation;
        this.tenantAccessService = tenantAccessService;
        setMessageNotFound("error.Company.not.found");
        this.mapper = mapper;
    }

    @Override
    public UserCompany checkExist(UserCompanyId id) {
        UserCompany userCompany = super.checkExist(id);
        tenantAccessService.assertCompanyAccess(userCompany.getCompany());
        return userCompany;
    }

    @Override
    protected void checkOwnerFieldsToCreate(UserCompany entity) {
        entity.setDeleted(false);
    }

    @Override
    protected UserCompany checkUpdateConsistence(UserCompanyId id, UserCompany toUpdateEntity) {
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
    public UserCompany checkRelations(UserCompany toPersistEntity) {
        checkUser(toPersistEntity);
        checkCompany(toPersistEntity);
        return toPersistEntity;
    }

    public UserCompany checkUser(UserCompany toPersistEntity) {
        if (Objects.isNull(toPersistEntity.getUser())) {
            return toPersistEntity;
        }

        if (Objects.isNull(toPersistEntity.getUser().getId())) {

            toPersistEntity.setUser(null);
            return toPersistEntity;
        }

        var rel = this.userValidation.checkExist(toPersistEntity.getUser().getId());

        toPersistEntity.setUser(rel);

        return toPersistEntity;
    }

    public UserCompany checkCompany(UserCompany toPersistEntity) {
        if (Objects.isNull(toPersistEntity.getCompany())) {
            return toPersistEntity;
        }

        if (Objects.isNull(toPersistEntity.getCompany().getId())) {

            toPersistEntity.setCompany(null);
            return toPersistEntity;
        }

        var rel = this.companyValidation.checkExist(toPersistEntity.getCompany().getId());

        toPersistEntity.setCompany(rel);

        return toPersistEntity;
    }
}
