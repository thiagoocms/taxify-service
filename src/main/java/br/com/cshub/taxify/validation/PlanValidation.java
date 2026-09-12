package br.com.cshub.taxify.validation;

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

    @Autowired
    public PlanValidation(PlanMapper mapper, PlanRepository repository, MessageService messageService) {
        super(repository, messageService);
        setMessageNotFound("error.plan.not.found");
        this.mapper = mapper;
    }

    @Override
    protected void checkOwnerFieldsToCreate(Plan entity) {
        entity.setId(null);
        entity.setDeleted(false);
    }

    @Override
    protected Plan checkUpdateConsistence(UUID id, Plan toUpdateEntity) {
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
