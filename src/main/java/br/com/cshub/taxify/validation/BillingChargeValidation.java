package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.mapper.BillingChargeMapper;
import br.com.cshub.taxify.repository.BillingChargeRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Manual charges (fees typed in Taxify - the MANUAL provider, or something the provider
 * doesn't have). Charges synced from a provider are read-only here: the next sync would
 * overwrite any edit.
 */
@Component
public class BillingChargeValidation extends AbstractCrudValidation<BillingCharge, UUID> {

    private final BillingChargeMapper mapper;
    private final CompanyValidation companyValidation;
    private final SubscriptionValidation subscriptionValidation;
    private final TenantAccessService tenantAccessService;

    public BillingChargeValidation(BillingChargeRepository repository, MessageService messageService, BillingChargeMapper mapper,
                                   CompanyValidation companyValidation, SubscriptionValidation subscriptionValidation,
                                   TenantAccessService tenantAccessService) {
        super(repository, messageService);
        setMessageNotFound("error.billing.charge.not.found");
        this.mapper = mapper;
        this.companyValidation = companyValidation;
        this.subscriptionValidation = subscriptionValidation;
        this.tenantAccessService = tenantAccessService;
    }

    @Override
    public BillingCharge checkExist(UUID id) {
        BillingCharge charge = super.checkExist(id);
        tenantAccessService.assertCompanyAccess(charge.getCompany());
        return charge;
    }

    @Override
    protected void checkOwnerFieldsToCreate(BillingCharge entity) {
        entity.setId(null);
        entity.setDeleted(false);
        entity.setSource(BillingProviderType.MANUAL);
        entity.setExternalId(null);
        entity.setLastSyncedAt(null);
    }

    @Override
    protected BillingCharge checkUpdateConsistence(UUID id, BillingCharge toUpdateEntity) {
        if (Objects.isNull(id) || Objects.isNull(toUpdateEntity.getId()) || id.compareTo(toUpdateEntity.getId()) != 0) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }
        BillingCharge persisted = checkExist(id);
        assertManual(persisted);
        mapper.merge(toUpdateEntity, persisted);
        return persisted;
    }

    /** Synced charges belong to the provider. */
    public void assertManual(BillingCharge charge) {
        if (charge.getSource() != BillingProviderType.MANUAL) {
            throw new BadRequestException("error.billing.charge.synced", messageService.getMessage("error.billing.charge.synced"));
        }
    }

    @Override
    public BillingCharge checkRelations(BillingCharge charge) {
        charge.setCompany(companyValidation.checkExist(charge.getCompany().getId()));
        if (charge.getSubscription() != null) {
            Subscription subscription = subscriptionValidation.checkExist(charge.getSubscription().getId());
            if (!subscription.getCompany().getId().equals(charge.getCompany().getId())) {
                throw new BadRequestException("error.billing.charge.subscription.company", messageService.getMessage("error.billing.charge.subscription.company"));
            }
            charge.setSubscription(subscription);
        }
        return charge;
    }

    /** Open amount and status follow the amounts and the due date; only "canceled" is a choice. */
    @Override
    protected void checkValidationsExtras(BillingCharge charge) {
        BigDecimal paid = charge.getPaidAmount() == null ? BigDecimal.ZERO : charge.getPaidAmount();
        if (paid.compareTo(charge.getAmount()) > 0) {
            throw new BadRequestException("error.billing.charge.overpaid", messageService.getMessage("error.billing.charge.overpaid"));
        }
        charge.setPaidAmount(paid);
        charge.setOpenAmount(charge.getAmount().subtract(paid));
        charge.setStatus(statusOf(charge, LocalDate.now()));
    }

    static ChargeStatus statusOf(BillingCharge charge, LocalDate today) {
        if (charge.getStatus() == ChargeStatus.CANCELED) {
            return ChargeStatus.CANCELED;
        }
        if (charge.getOpenAmount().signum() == 0) {
            return ChargeStatus.PAID;
        }
        if (charge.getDueDate().isBefore(today)) {
            return ChargeStatus.OVERDUE;
        }
        return charge.getPaidAmount().signum() > 0 ? ChargeStatus.PARTIALLY_PAID : ChargeStatus.OPEN;
    }
}
