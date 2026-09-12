package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.auth.Auth;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.user.UserDTO;
import br.com.cshub.taxify.dto.user.UserFilterDTO;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.mapper.UserMapper;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.search.UserSpecification;
import br.com.cshub.taxify.validation.UserValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService extends AbstractCrudService<User, UUID, UserDTO, UserFilterDTO> implements UserDetailsService {


    private final UserRepository repository;
    private final TenantAccessService tenantAccessService;


    @Autowired
    protected UserService(UserRepository repository, UserMapper mapper, UserValidation validation, MessageService messageService, UserSpecification userSpecification, TenantAccessService tenantAccessService) {
        super(repository, mapper, validation, messageService, userSpecification);
        setMessageNotFound("error.user.not.found");
        this.repository = repository;
        this.tenantAccessService = tenantAccessService;
    }

    @Override
    public Auth loadUserByUsername(String username) throws UsernameNotFoundException {

        var user = repository.findByLoginAndDeletedIsFalse(username);
        if (user == null) {
            throw new ResourceNotFoundException(messageService.getMessage("error.user.not.found"));
        }

        return new Auth(user);
    }

    /**
     * A user only ever needs to see teammates who belong to their own accounting firm -
     * not the whole users table across every firm using the system.
     */
    @Override
    protected void checkAccess(User entity) {
        tenantAccessService.assertSameFirm(entity.getAccountingFirm().getId());
    }

    @Override
    protected Specification<User> restrictQuery(Specification<User> spec) {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        if (firmId.isEmpty()) {
            return spec;
        }
        Specification<User> restriction = (root, query, builder) -> builder.equal(root.get("accountingFirm").get("id"), firmId.get());
        return spec == null ? restriction : spec.and(restriction);
    }
}
