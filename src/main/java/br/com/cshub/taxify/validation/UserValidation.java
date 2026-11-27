package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.common.AbstractCrudValidation;
import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import java.nio.charset.StandardCharsets;
import br.com.cshub.taxify.mapper.UserMapper;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.firmaccess.PlanLimitService;
import org.springframework.security.access.AccessDeniedException;
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
    private final PlanLimitService planLimitService;
    static final int PASSWORD_MAX_BYTES = 72;

    @Autowired
    public UserValidation(UserMapper mapper, UserRepository repository, MessageService messageService, DocumentValidation documentValidation, PasswordEncoder passwordEncoder,
                           CurrentUserService currentUserService, AccountingFirmRepository accountingFirmRepository, TenantAccessService tenantAccessService,
                           PlanLimitService planLimitService) {
        super(repository, messageService);
        this.planLimitService = planLimitService;
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
     * Resolves who this user belongs to: the logged-in ADMIN creating a teammate joins them to their own firm.
     * POST /users is no longer the public signup (F1-RN-01) - that is POST /signup, which builds the firm and its
     * ADMIN itself; without a logged-in caller nothing is created here. The trial limit (F1-RN-10a) is checked before
     * anything is written.
     */
    @Override
    protected void checkOwnerFieldsToCreate(User entity) {
        entity.setId(null);
        entity.setDeleted(false);
        if (Objects.isNull(entity.getPassword()) || entity.getPassword().isBlank()) {
            throw new BadRequestException("error.user.password.required", messageService.getMessage("error.user.password.required"));
        }
        assertPasswordFits(entity.getPassword());
        entity.setPassword(passwordEncoder.encode(entity.getPassword()));

        var currentUser = currentUserService.getCurrentUser()
                .orElseThrow(() -> new AccessDeniedException("POST /users needs a logged-in ADMIN - the public signup is POST /signup"));
        // Only an ADMIN gets here (SecurityConfig) - the teammate joins the admin's firm.
        entity.setAccountingFirm(currentUser.getAccountingFirm());
        planLimitService.assertUserSlot(currentUser.getAccountingFirm().getId());
        clearProfilesOfAdmin(entity);
    }

    /**
     * A firm must always keep at least one ADMIN - otherwise nobody could manage its users,
     * integrations or settings anymore. Called before demoting or deleting an ADMIN.
     */
    public void assertNotLastAdmin(User current) {
        if (current.getProfile() != UserProfileEnum.ADMIN) {
            return;
        }
        long admins = repository.countByAccountingFirm_IdAndProfileAndDeletedIsFalse(
                current.getAccountingFirm().getId(), UserProfileEnum.ADMIN);
        if (admins <= 1) {
            throw new BadRequestException("error.user.last.admin", messageService.getMessage("error.user.last.admin"));
        }
    }

    /** ADMIN is a special type that can do everything - access profiles don't apply to it. */
    private static void clearProfilesOfAdmin(User user) {
        if (user.getProfile() == UserProfileEnum.ADMIN) {
            user.setAccessProfiles(null);
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
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }

        if (id.compareTo(toUpdateEntity.getId()) != 0) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }

        var persistedEntity = checkExist(id);
        if (toUpdateEntity.getProfile() != UserProfileEnum.ADMIN || Boolean.TRUE.equals(toUpdateEntity.isDeleted())) {
            assertNotLastAdmin(persistedEntity);
        }

        mapper.merge(toUpdateEntity, persistedEntity);
        clearProfilesOfAdmin(persistedEntity);

        // The API never returns the password, so a blank one on update means "keep the
        // current one"; a new one is hashed here, same as on create.
        String newPassword = toUpdateEntity.getPassword();
        if (Objects.nonNull(newPassword) && !newPassword.isBlank()) {
            assertPasswordFits(newPassword);
            persistedEntity.setPassword(passwordEncoder.encode(newPassword));
        }

        try {
            persistedEntity.setDocument(
                    documentValidation.updateNumberAndType(persistedEntity.getDocument(), toUpdateEntity.getDocument()));
        } catch (ConflictException e) {
            throw UserConflicts.documentTaken(messageService);
        }

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
            try {
                this.documentValidation.checkExistByNumberAndType(toPersistEntity.getDocument());
            } catch (ConflictException e) {
                // the CPF of another user - 409 with a code, same as POST /signup
                throw UserConflicts.documentTaken(messageService);
            }
            var document = this.documentValidation.saveRelation(toPersistEntity.getDocument());
            toPersistEntity.setDocument(document);
            return toPersistEntity;
        }

        var rel = this.documentValidation.checkExist(toPersistEntity.getDocument().getId());

        toPersistEntity.setDocument(rel);

        return toPersistEntity;
    }

    /** 409 error.user.login.already.exists, same as POST /signup. Deleted users' logins count too, on create and on edit (uk_users_login). */
    @Override
    protected void checkValidationsExtras(User entity) {
        boolean taken = entity.getId() == null
                ? repository.existsByLogin(entity.getLogin())
                : repository.existsByLoginAndIdNot(entity.getLogin(), entity.getId());
        if (taken) {
            throw UserConflicts.loginTaken(messageService);
        }
    }

    /** BCrypt only takes 72 bytes (Spring refuses longer ones with an exception - it was a 500). */
    private void assertPasswordFits(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
            throw new BadRequestException("error.user.password.invalid", messageService.getMessage("error.user.password.invalid"));
        }
    }
}
