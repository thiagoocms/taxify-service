package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.ProductFiscalRuleMapper;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class ProductFiscalRuleValidation extends AbstractCrudValidation<ProductFiscalRule, UUID> {

    private final ProductFiscalRuleMapper mapper;
    private final ProductFiscalRuleRepository repository;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;

    @Autowired
    public ProductFiscalRuleValidation(ProductFiscalRuleMapper mapper, ProductFiscalRuleRepository repository,
                                        MessageService messageService, CompanyValidation companyValidation, TenantAccessService tenantAccessService) {
        super(repository, messageService);
        setMessageNotFound("error.productFiscalRule.not.found");
        this.mapper = mapper;
        this.repository = repository;
        this.companyValidation = companyValidation;
        this.tenantAccessService = tenantAccessService;
    }

    @Override
    public ProductFiscalRule checkExist(UUID id) {
        ProductFiscalRule rule = super.checkExist(id);
        tenantAccessService.assertSameFirm(rule.getCompany().getAccountingFirm().getId());
        return rule;
    }

    @Override
    protected void checkOwnerFieldsToCreate(ProductFiscalRule entity) {
        entity.setId(null);
        entity.setDeleted(false);
    }

    @Override
    protected ProductFiscalRule checkUpdateConsistence(UUID id, ProductFiscalRule toUpdateEntity) {
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
    public ProductFiscalRule checkRelations(ProductFiscalRule toPersistEntity) {
        checkCompany(toPersistEntity);
        return toPersistEntity;
    }

    public ProductFiscalRule checkCompany(ProductFiscalRule toPersistEntity) {
        if (Objects.isNull(toPersistEntity.getCompany()) || Objects.isNull(toPersistEntity.getCompany().getId())) {
            return toPersistEntity;
        }

        var rel = this.companyValidation.checkExist(toPersistEntity.getCompany().getId());
        toPersistEntity.setCompany(rel);
        return toPersistEntity;
    }

    @Override
    protected void checkValidationsExtras(ProductFiscalRule entity) {
        boolean duplicate = entity.getId() == null
                ? repository.existsByCompany_IdAndCodigoProdutoAndDeletedIsFalse(entity.getCompany().getId(), entity.getCodigoProduto())
                : repository.existsByCompany_IdAndCodigoProdutoAndDeletedIsFalseAndIdNot(
                        entity.getCompany().getId(), entity.getCodigoProduto(), entity.getId());

        if (duplicate) {
            throw new ConflictException(messageService.getMessage("error.productFiscalRule.duplicate"));
        }
    }
}
