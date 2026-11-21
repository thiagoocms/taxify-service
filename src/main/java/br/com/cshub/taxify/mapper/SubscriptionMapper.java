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
        entity.setFee(source.fee());
        entity.setBillingCycle(source.billingCycle());
        entity.setBillingDay(source.billingDay());
        entity.setStatus(source.status());
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
                source.getFee(),
                source.getBillingCycle(),
                source.getBillingDay(),
                source.getStatus(),
                source.getSource(),
                source.getExternalContractId(),
                source.getExternalContractNumber(),
                source.getNextDueDate(),
                source.getLastSyncedAt(),
                source.getSyncWarning(),
                source.isDeleted()
        );
    }

    @Override
    public void merge(Subscription source, Subscription target) {
        target.setCompany(source.getCompany());
        target.setPlan(source.getPlan());
        target.setStartDate(source.getStartDate());
        target.setEndDate(source.getEndDate());
        target.setBillingCycle(source.getBillingCycle());
        // Contract data of a synced subscription belongs to the provider - a manual edit
        // must not overwrite it (the next sync would just put it back anyway).
        if (!target.isSynced()) {
            target.setFee(source.getFee());
            target.setBillingDay(source.getBillingDay());
            target.setStatus(source.getStatus());
        }
        target.setDeleted(source.isDeleted());
    }
}
