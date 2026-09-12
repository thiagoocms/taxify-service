package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.AccountingFirmMapper;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class AccountingFirmValidation extends AbstractCrudValidation<AccountingFirm, UUID> {

    private final AccountingFirmMapper mapper;
    private final TenantAccessService tenantAccessService;

    @Autowired
    public AccountingFirmValidation(AccountingFirmMapper mapper, AccountingFirmRepository repository, MessageService messageService, TenantAccessService tenantAccessService) {
        super(repository, messageService);
        this.mapper = mapper;
        this.tenantAccessService = tenantAccessService;
        setMessageNotFound("error.accountingFirm.not.found");
    }

    /**
     * An AccountingFirm's own id IS the tenant id, so this only needs a direct equality
     * check against the current user's firm - no join required (unlike CompanyValidation).
     */
    @Override
    public AccountingFirm checkExist(UUID id) {
        AccountingFirm firm = super.checkExist(id);
        tenantAccessService.assertSameFirm(firm.getId());
        return firm;
    }

    @Override
    protected void checkOwnerFieldsToCreate(AccountingFirm entity) {
        entity.setId(null);
        entity.setDeleted(false);
    }

    @Override
    protected AccountingFirm checkUpdateConsistence(UUID id, AccountingFirm toUpdateEntity) {
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
}
