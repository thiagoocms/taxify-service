package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.UserMapper;
import br.com.cshub.taxify.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class UserValidation extends AbstractCrudValidation<User, UUID> {

    private final UserMapper mapper;
    private final UserRepository repository;
    private final DocumentValidation documentValidation;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserValidation(UserMapper mapper, UserRepository repository, MessageService messageService, DocumentValidation documentValidation, PasswordEncoder passwordEncoder) {
        super(repository, messageService);
        this.documentValidation = documentValidation;
        this.passwordEncoder = passwordEncoder;
        setMessageNotFound("error.user.not.found");
        this.mapper = mapper;
        this.repository = repository;
    }

    @Override
    protected void checkOwnerFieldsToCreate(User entity) {
        entity.setId(null);
        entity.setDeleted(false);
        entity.setPassword(passwordEncoder.encode(entity.getPassword()));
    }

    @Override
    protected User checkUpdateConsistence(UUID id, User toUpdateEntity) {
        if (Objects.isNull(id) || Objects.isNull(toUpdateEntity.getId())) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }

        if (id.compareTo(toUpdateEntity.getId()) != 0) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }

        var persistedEntity = checkExist(id);

        mapper.merge(toUpdateEntity, persistedEntity);

        return persistedEntity;
    }

    @Override
    public User checkRelations(User toPersistEntity) {
        checkDocument(toPersistEntity);
        return toPersistEntity;
    }

    public User checkDocument(User toPersistEntity) {
        if (Objects.isNull(toPersistEntity.getDocument())) {
            return toPersistEntity;
        }

        if (Objects.isNull(toPersistEntity.getDocument().getId())) {
            this.documentValidation.checkExistByNumberAndType(toPersistEntity.getDocument());
            var document = this.documentValidation.saveRelation(toPersistEntity.getDocument());
            toPersistEntity.setDocument(document);
            return toPersistEntity;
        }

        var rel = this.documentValidation.checkExist(toPersistEntity.getDocument().getId());

        toPersistEntity.setDocument(rel);

        return toPersistEntity;
    }

    @Override
    protected void checkValidationsExtras(User entity) {
        var count = repository.countByLoginAndDeletedIsFalse(entity.getId(), entity.getLogin());
        if (count > 0) {
            throw new ConflictException(messageService.getMessage("error.user.login.already.exists"));
        }
    }
}
