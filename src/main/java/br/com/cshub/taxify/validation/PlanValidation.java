package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.PlanMapper;
import br.com.cshub.taxify.repository.PlanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class PlanValidation extends AbstractCrudValidation<Plan, UUID> {

    private final PlanMapper mapper;
    private final TenantAccessService tenantAccessService;
    private final CurrentUserService currentUserService;

    @Autowired
    public PlanValidation(PlanMapper mapper, PlanRepository repository, MessageService messageService,
                          TenantAccessService tenantAccessService, CurrentUserService currentUserService) {
        super(repository, messageService);
        setMessageNotFound("error.plan.not.found");
        this.mapper = mapper;
        this.tenantAccessService = tenantAccessService;
        this.currentUserService = currentUserService;
    }

    /** Another firm's plan answers as not found - also when a subscription points to it. */
    @Override
    public Plan checkExist(UUID id) {
        Plan plan = super.checkExist(id);
        tenantAccessService.assertSameFirm(plan.getAccountingFirm() == null ? null : plan.getAccountingFirm().getId());
        return plan;
    }

    @Override
    protected void checkOwnerFieldsToCreate(Plan entity) {
        entity.setId(null);
        entity.setDeleted(false);
        currentUserService.getCurrentUser().ifPresent(user -> entity.setAccountingFirm(user.getAccountingFirm()));
    }

    @Override
    protected Plan checkUpdateConsistence(UUID id, Plan toUpdateEntity) {
        if (Objects.isNull(id) || Objects.isNull(toUpdateEntity.getId())) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }

        if (id.compareTo(toUpdateEntity.getId()) != 0) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }

        var persistedEntity = checkExist(id);

        mapper.merge(toUpdateEntity, persistedEntity);

        return persistedEntity;
    }
}
