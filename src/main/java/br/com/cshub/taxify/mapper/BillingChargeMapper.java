package br.com.cshub.taxify.mapper;

import br.com.cshub.taxify.common.AbstractMapper;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.dto.billing.BillingChargeDTO;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BillingChargeMapper extends AbstractMapper<BillingCharge, BillingChargeDTO> {

    @Override
    protected BillingCharge toEntity(BillingChargeDTO source) {
        BillingCharge entity = new BillingCharge();
        entity.setId(source.id() != null ? UUID.fromString(source.id()) : null);
        entity.setCompany(source.companyId() != null ? new Company(UUID.fromString(source.companyId())) : null);
        entity.setSubscription(source.subscriptionId() != null && !source.subscriptionId().isBlank()
                ? new Subscription(UUID.fromString(source.subscriptionId())) : null);
        entity.setDescription(source.description());
        entity.setCompetenceDate(source.competenceDate());
        entity.setDueDate(source.dueDate());
        entity.setAmount(source.amount());
        entity.setPaidAmount(source.paidAmount());
        entity.setStatus(source.status());
        return entity;
    }

    @Override
    public BillingChargeDTO toModel(BillingCharge charge) {
        return new BillingChargeDTO(
                charge.getId().toString(),
                charge.getCompany().getId().toString(),
                charge.getSubscription() == null ? null : charge.getSubscription().getId().toString(),
                charge.getSource(),
                charge.getDescription(),
                charge.getCompetenceDate(),
                charge.getDueDate(),
                charge.getAmount(),
                charge.getPaidAmount(),
                charge.getOpenAmount(),
                charge.getStatus());
    }

    /** What a manual charge's edit can change - never the company or the source. */
    @Override
    public void merge(BillingCharge source, BillingCharge target) {
        target.setSubscription(source.getSubscription());
        target.setDescription(source.getDescription());
        target.setCompetenceDate(source.getCompetenceDate());
        target.setDueDate(source.getDueDate());
        target.setAmount(source.getAmount());
        target.setPaidAmount(source.getPaidAmount());
        target.setStatus(source.getStatus());
    }
}
