package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.billing.BillingChargeDTO;
import br.com.cshub.taxify.dto.billing.BillingChargeFilterDTO;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.service.BillingChargeService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Fee charges (honorários): list all; create/edit/delete the manual ones. */
@RequiresPermission(value = Permission.BILLING_VIEW, write = Permission.BILLING_MANAGE)
@RestController
@RequestMapping(AppConstants.API + AppConstants.V1 + "/billing/charges")
public class BillingChargeResource extends AbstractCrudResource<UUID, BillingChargeDTO, BillingChargeFilterDTO> {

    public BillingChargeResource(BillingChargeService service) {
        super(service);
    }
}
