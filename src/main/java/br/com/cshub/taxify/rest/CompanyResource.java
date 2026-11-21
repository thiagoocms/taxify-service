package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.security.AdminOnly;
import br.com.cshub.taxify.security.RequiresPermission;
import br.com.cshub.taxify.enumerated.Permission;
import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.company.CompanyDTO;
import br.com.cshub.taxify.dto.company.CompanyFilterDTO;
import br.com.cshub.taxify.service.CompanyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RequiresPermission(Permission.COMPANIES_VIEW)
@AdminOnly(writeOnly = true)
@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/companies")
public class CompanyResource extends AbstractCrudResource<UUID, CompanyDTO, CompanyFilterDTO> {

    @Autowired
    protected CompanyResource(CompanyService service) {
        super(service);
    }
}
