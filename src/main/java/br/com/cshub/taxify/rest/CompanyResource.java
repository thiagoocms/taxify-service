package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.company.CompanyDTO;
import br.com.cshub.taxify.dto.company.CompanyFilterDTO;
import br.com.cshub.taxify.service.CompanyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/companies")
public class CompanyResource extends AbstractCrudResource<UUID, CompanyDTO, CompanyFilterDTO> {

    @Autowired
    protected CompanyResource(CompanyService service) {
        super(service);
    }
}
