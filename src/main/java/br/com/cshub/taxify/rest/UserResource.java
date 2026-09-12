package br.com.cshub.taxify.rest;

import br.com.cshub.taxify.common.AbstractCrudResource;
import br.com.cshub.taxify.constants.AppConstants;
import br.com.cshub.taxify.dto.user.UserDTO;
import br.com.cshub.taxify.dto.user.UserFilterDTO;
import br.com.cshub.taxify.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(value = AppConstants.API + AppConstants.V1 + "/users")
public class UserResource extends AbstractCrudResource<UUID, UserDTO, UserFilterDTO> {

    @Autowired
    protected UserResource(UserService service) {
        super(service);
    }
}
