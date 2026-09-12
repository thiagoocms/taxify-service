package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.invoice.InvoiceAuditLog;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.InvoiceAuditLogMapper;
import br.com.cshub.taxify.repository.InvoiceAuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class InvoiceAuditLogValidation extends AbstractCrudValidation<InvoiceAuditLog, UUID> {

    private final InvoiceAuditLogMapper mapper;
    private final UserValidation userValidation;
    private final CompanyValidation companyValidation;
    private final TenantAccessService tenantAccessService;

    @Autowired
    public InvoiceAuditLogValidation(InvoiceAuditLogMapper mapper, InvoiceAuditLogRepository repository, MessageService messageService, UserValidation userValidation, CompanyValidation companyValidation, TenantAccessService tenantAccessService) {
        super(repository, messageService);
        this.userValidation = userValidation;
        this.companyValidation = companyValidation;
        this.tenantAccessService = tenantAccessService;
        setMessageNotFound("error.invoice.audit.log.not.found");
        this.mapper = mapper;
    }

    @Override
    public InvoiceAuditLog checkExist(UUID id) {
        InvoiceAuditLog log = super.checkExist(id);
        tenantAccessService.assertSameFirm(log.getCompany().getAccountingFirm().getId());
        return log;
    }

    @Override
    protected void checkOwnerFieldsToCreate(InvoiceAuditLog entity) {
        entity.setDeleted(false);
    }

    @Override
    protected InvoiceAuditLog checkUpdateConsistence(UUID id, InvoiceAuditLog toUpdateEntity) {
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
    public InvoiceAuditLog checkRelations(InvoiceAuditLog toPersistEntity) {
        checkUser(toPersistEntity);
        checkCompany(toPersistEntity);
        return toPersistEntity;
    }

    public InvoiceAuditLog checkUser(InvoiceAuditLog toPersistEntity) {
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

    public InvoiceAuditLog checkCompany(InvoiceAuditLog toPersistEntity) {
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
