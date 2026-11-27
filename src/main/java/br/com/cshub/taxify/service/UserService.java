package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.FirmScopedCrudService;
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
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.validation.UserValidation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService extends FirmScopedCrudService<User, UUID, UserDTO, UserFilterDTO> implements UserDetailsService {


    private final UserRepository repository;
    private final UserValidation userValidation;
    private final FirmAccessService firmAccessService;


    @Autowired
    protected UserService(UserRepository repository, UserMapper mapper, UserValidation validation, MessageService messageService, UserSpecification userSpecification, TenantAccessService tenantAccessService,
                          FirmAccessService firmAccessService) {
        super(repository, mapper, validation, messageService, userSpecification, tenantAccessService,
                user -> user.getAccountingFirm().getId(), root -> root.get("accountingFirm").get("id"));
        setMessageNotFound("error.user.not.found");
        this.repository = repository;
        this.userValidation = validation;
        this.firmAccessService = firmAccessService;
    }

    /** Soft delete - but never the firm's last ADMIN (see UserValidation#assertNotLastAdmin). */
    @Override
    public void delete(UUID id) {
        userValidation.assertNotLastAdmin(findCheckById(id));
        super.delete(id);
    }

    @Override
    public Auth loadUserByUsername(String username) throws UsernameNotFoundException {

        var user = repository.findByLoginAndDeletedIsFalse(username);
        if (user == null) {
            throw new ResourceNotFoundException("error.user.not.found", messageService.getMessage("error.user.not.found"));
        }

        // F1-RN-03: a firm whose e-mail is not verified has no login (see Auth#isCredentialsNonExpired)
        boolean pending = user.getAccountingFirm() != null
                && firmAccessService.accessOf(user.getAccountingFirm().getId()).pendingVerification();
        return new Auth(user, pending);
    }

    /**
     * A user only ever needs to see teammates who belong to their own accounting firm -
     * not the whole users table across every firm using the system.
     */

}
