package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.dto.subscription.SubscriptionDTO;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SubscriptionMapper extends AbstractMapper<Subscription, SubscriptionDTO> {

    @Override
    protected Subscription toEntity(SubscriptionDTO source) {
        Subscription entity = new Subscription();
        entity.setId(source.id() != null ? UUID.fromString(source.id()) : null);
        entity.setCompany(source.companyId() != null ? new Company(UUID.fromString(source.companyId())) : null);
        entity.setPlan(source.planId() != null ? new Plan(UUID.fromString(source.planId())) : null);
        entity.setStartDate(source.startDate());
        entity.setEndDate(source.endDate());
        entity.setDeleted(Boolean.TRUE.equals(source.deleted()));
        return entity;
    }

    @Override
    protected SubscriptionDTO toModel(Subscription source) {
        return new SubscriptionDTO(
                source.getId().toString(),
                source.getCompany().getId().toString(),
                source.getPlan().getId().toString(),
                source.getStartDate(),
                source.getEndDate(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(Subscription source, Subscription target) {
        target.setCompany(source.getCompany());
        target.setPlan(source.getPlan());
        target.setStartDate(source.getStartDate());
        target.setEndDate(source.getEndDate());
        target.setDeleted(source.isDeleted());
    }
}
