package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.UserMapper;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
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
    private final CurrentUserService currentUserService;
    private final AccountingFirmRepository accountingFirmRepository;
    private final TenantAccessService tenantAccessService;

    @Autowired
    public UserValidation(UserMapper mapper, UserRepository repository, MessageService messageService, DocumentValidation documentValidation, PasswordEncoder passwordEncoder,
                           CurrentUserService currentUserService, AccountingFirmRepository accountingFirmRepository, TenantAccessService tenantAccessService) {
        super(repository, messageService);
        this.documentValidation = documentValidation;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
        this.accountingFirmRepository = accountingFirmRepository;
        this.tenantAccessService = tenantAccessService;
        setMessageNotFound("error.user.not.found");
        this.mapper = mapper;
        this.repository = repository;
    }

    /**
     * Resolves who this user belongs to. A logged-in caller creating this user (e.g. an
     * accounting firm adding a teammate) joins them to the caller's own firm; a public,
     * unauthenticated signup (POST /users is permitAll) instead starts a brand-new firm,
     * named after the signing-up user for now.
     */
    @Override
    protected void checkOwnerFieldsToCreate(User entity) {
        entity.setId(null);
        entity.setDeleted(false);
        entity.setPassword(passwordEncoder.encode(entity.getPassword()));

        var currentUser = currentUserService.getCurrentUser();
        if (currentUser.isPresent()) {
            entity.setAccountingFirm(currentUser.get().getAccountingFirm());
        } else {
            AccountingFirm firm = new AccountingFirm();
            firm.setName(entity.getName());
            firm.setDeleted(false);
            accountingFirmRepository.save(firm);
            entity.setAccountingFirm(firm);
        }
    }

    /**
     * A user reference is only ever resolved either for the caller's own profile or as a
     * relation on another entity scoped to the caller's firm (an InvoiceAuditLog author, a
     * new UserCompany member) - in every case the target must belong to the same firm.
     */
    @Override
    public User checkExist(UUID id) {
        User user = super.checkExist(id);
        tenantAccessService.assertSameFirm(user.getAccountingFirm().getId());
        return user;
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
