package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CompanyScopedCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.dto.billing.BillingChargeDTO;
import br.com.cshub.taxify.dto.billing.BillingChargeFilterDTO;
import br.com.cshub.taxify.mapper.BillingChargeMapper;
import br.com.cshub.taxify.repository.BillingChargeRepository;
import br.com.cshub.taxify.search.BillingChargeSpecification;
import br.com.cshub.taxify.validation.BillingChargeValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Fee charges: every charge is listed; only manual ones are created, edited or deleted here. */
@Service
public class BillingChargeService extends CompanyScopedCrudService<BillingCharge, UUID, BillingChargeDTO, BillingChargeFilterDTO> {

    private final BillingChargeValidation chargeValidation;

    public BillingChargeService(BillingChargeRepository repository, BillingChargeMapper mapper, BillingChargeValidation validation,
                                MessageService messageService, BillingChargeSpecification specification,
                                TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, specification, tenantAccessService,
                BillingCharge::getCompany, root -> root.get("company"));
        setMessageNotFound("error.billing.charge.not.found");
        this.chargeValidation = validation;
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        chargeValidation.assertManual(findCheckById(id));
        super.delete(id);
    }
}
