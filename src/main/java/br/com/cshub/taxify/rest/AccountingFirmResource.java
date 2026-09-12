package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.accountingfirm.AccountingFirmDTO;
import br.com.cshub.taxify.dto.accountingfirm.AccountingFirmFilterDTO;
import br.com.cshub.taxify.service.AccountingFirmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/accounting-firms")
public class AccountingFirmResource extends AbstractCrudResource<UUID, AccountingFirmDTO, AccountingFirmFilterDTO> {

    @Autowired
    protected AccountingFirmResource(AccountingFirmService service) {
        super(service);
    }
}
