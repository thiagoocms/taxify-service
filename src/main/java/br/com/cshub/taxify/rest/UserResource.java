package br.com.cshub.taxify.rest;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import br.com.cshub.taxify.security.AdminOnly;
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

    // Creating a user stays in SecurityConfig: it's the public signup (anonymous) or an ADMIN.

    @AdminOnly
    @Override
    public ResponseEntity<UserDTO> update(@PathVariable UUID id, @Valid @RequestBody UserDTO dto) {
        return super.update(id, dto);
    }

    @AdminOnly
    @Override
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return super.delete(id);
    }
}
