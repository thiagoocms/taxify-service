package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.AbstractCrudService;
import br.com.cshub.taxify.common.MessageService;
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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService extends AbstractCrudService<User, UUID, UserDTO, UserFilterDTO> implements UserDetailsService {


    private final UserRepository repository;


    @Autowired
    protected UserService(UserRepository repository, UserMapper mapper, UserValidation validation, MessageService messageService, UserSpecification userSpecification) {
        super(repository, mapper, validation, messageService, userSpecification);
        setMessageNotFound("error.user.not.found");
        this.repository = repository;
    }

    @Override
    public Auth loadUserByUsername(String username) throws UsernameNotFoundException {

        var user = repository.findByLoginAndDeletedIsFalse(username);
        if (user == null) {
            throw new ResourceNotFoundException(messageService.getMessage("error.user.not.found"));
        }

        return new Auth(user);
    }
}
