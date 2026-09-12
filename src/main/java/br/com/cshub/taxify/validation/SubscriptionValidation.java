package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.SubscriptionMapper;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class SubscriptionValidation extends AbstractCrudValidation<Subscription, UUID> {

    private final SubscriptionMapper mapper;
    private final SubscriptionRepository repository;
    private final CompanyValidation companyValidation;
    private final PlanValidation planValidation;

    @Autowired
    public SubscriptionValidation(SubscriptionMapper mapper, SubscriptionRepository repository, MessageService messageService,
                                   CompanyValidation companyValidation, PlanValidation planValidation) {
        super(repository, messageService);
        setMessageNotFound("error.subscription.not.found");
        this.mapper = mapper;
        this.repository = repository;
        this.companyValidation = companyValidation;
        this.planValidation = planValidation;
    }

    @Override
    protected void checkOwnerFieldsToCreate(Subscription entity) {
        entity.setId(null);
        entity.setDeleted(false);
    }

    @Override
    protected Subscription checkUpdateConsistence(UUID id, Subscription toUpdateEntity) {
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
    public Subscription checkRelations(Subscription toPersistEntity) {
        checkCompany(toPersistEntity);
        checkPlan(toPersistEntity);
        return toPersistEntity;
    }

    public Subscription checkCompany(Subscription toPersistEntity) {
        if (Objects.isNull(toPersistEntity.getCompany()) || Objects.isNull(toPersistEntity.getCompany().getId())) {
            return toPersistEntity;
        }

        var rel = this.companyValidation.checkExist(toPersistEntity.getCompany().getId());
        toPersistEntity.setCompany(rel);
        return toPersistEntity;
    }

    public Subscription checkPlan(Subscription toPersistEntity) {
        if (Objects.isNull(toPersistEntity.getPlan()) || Objects.isNull(toPersistEntity.getPlan().getId())) {
            return toPersistEntity;
        }

        var rel = this.planValidation.checkExist(toPersistEntity.getPlan().getId());
        toPersistEntity.setPlan(rel);
        return toPersistEntity;
    }

    @Override
    protected void checkValidationsExtras(Subscription entity) {
        if (entity.getStartDate().isAfter(entity.getEndDate())) {
            throw new BadRequestException(messageService.getMessage("error.subscription.invalid.dates"));
        }

        boolean overlaps = entity.getId() == null
                ? repository.existsByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        entity.getCompany().getId(), entity.getEndDate(), entity.getStartDate())
                : repository.existsByCompanyIdAndDeletedIsFalseAndIdNotAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        entity.getCompany().getId(), entity.getId(), entity.getEndDate(), entity.getStartDate());

        if (overlaps) {
            throw new ConflictException(messageService.getMessage("error.subscription.overlap"));
        }
    }
}
