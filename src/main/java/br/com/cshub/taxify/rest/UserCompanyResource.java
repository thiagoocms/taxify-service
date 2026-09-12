package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.domain.user_company.UserCompanyId;
import br.com.cshub.taxify.dto.user_company.UserCompanyDTO;
import br.com.cshub.taxify.dto.user_company.UserCompanyFilterDTO;
import br.com.cshub.taxify.service.UserCompanyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/users/{userId}/companies")
public class UserCompanyResource extends AbstractCrudResource<UserCompanyId, UserCompanyDTO, UserCompanyFilterDTO> {

    @Autowired
    protected UserCompanyResource(UserCompanyService service) {
        super(service);
    }
}
