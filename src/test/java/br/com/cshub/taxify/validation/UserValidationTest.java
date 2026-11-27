package br.com.cshub.taxify.validation;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.enumerated.AccessProfile;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.mapper.UserMapper;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserValidationTest {

    @Mock
    private UserMapper mapper;
    @Mock
    private UserRepository repository;
    @Mock
    private MessageService messageService;
    @Mock
    private DocumentValidation documentValidation;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private AccountingFirmRepository accountingFirmRepository;
    @Mock
    private TenantAccessService tenantAccessService;
    @Mock
    private br.com.cshub.taxify.service.firmaccess.PlanLimitService planLimitService;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private br.com.cshub.taxify.repository.PasswordResetTokenRepository resetTokens;

    private UserValidation validation;
    private AccountingFirm adminFirm;

    @BeforeEach
    void setUp() {
        adminFirm = new AccountingFirm(UUID.randomUUID());
        User admin = new User(UUID.randomUUID());
        admin.setAccountingFirm(adminFirm);
        admin.setProfile(UserProfileEnum.ADMIN);
        lenient().when(currentUserService.getCurrentUser()).thenReturn(Optional.of(admin));
        validation = new UserValidation(mapper, repository, messageService, documentValidation, passwordEncoder, currentUserService,
                accountingFirmRepository, tenantAccessService, planLimitService,
                new PasswordPolicy(new br.com.cshub.taxify.config.PasswordProperties(), passwordEncoder, messageService),
                eventPublisher, new br.com.cshub.taxify.service.firmaccess.MutableClock(java.time.LocalDateTime.of(2026, 11, 27, 10, 0)),
                resetTokens);
    }

    @Test
    void checkOwnerFieldsToCreateResetsIdAndDeletedAndEncodesPassword() {
        User entity = new User(UUID.randomUUID());
        entity.setDeleted(true);
        entity.setPassword("plain-pass");
        when(passwordEncoder.encode("plain-pass")).thenReturn("encoded");

        validation.checkOwnerFieldsToCreate(entity);

        assertThat(entity.getId()).isNull();
        assertThat(entity.isDeleted()).isFalse();
        assertThat(entity.getPassword()).isEqualTo("encoded");
    }

    /** CT1-07 (F1-RN-01): POST /users is no longer the public signup - without a logged-in ADMIN nothing is created. */
    @Test
    void withoutALoggedInAdminNoFirmNorUserIsCreated() {
        when(currentUserService.getCurrentUser()).thenReturn(Optional.empty());
        User entity = new User();
        entity.setPassword("plain-pass");
        when(passwordEncoder.encode("plain-pass")).thenReturn("encoded");

        assertThatThrownBy(() -> validation.checkOwnerFieldsToCreate(entity))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(accountingFirmRepository, never()).save(any());
    }

    /** CT1-28 (F1-RN-10a): the trial's user limit is checked for the admin's firm before the user is written. */
    @Test
    void theTrialUserLimitIsCheckedForTheAdminsFirm() {
        when(passwordEncoder.encode("plain-pass")).thenReturn("encoded");
        org.mockito.Mockito.doThrow(new ConflictException(
                "error.plan.limit.users", "limit")).when(planLimitService).assertUserSlot(adminFirm.getId());
        User entity = new User();
        entity.setPassword("plain-pass");

        assertThatThrownBy(() -> validation.checkOwnerFieldsToCreate(entity))
                .isInstanceOf(ConflictException.class)
                .hasMessage("limit");
    }

    @Test
    void adminAddingATeammateKeepsTheChosenTypeAndProfiles() {
        AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
        User admin = new User(UUID.randomUUID());
        admin.setAccountingFirm(firm);
        when(currentUserService.getCurrentUser()).thenReturn(Optional.of(admin));
        when(passwordEncoder.encode("plain-pass")).thenReturn("encoded");
        User entity = new User();
        entity.setPassword("plain-pass");
        entity.setProfile(UserProfileEnum.EMPLOYEE);
        entity.setAccessProfiles(EnumSet.of(AccessProfile.FISCAL, AccessProfile.FINANCIAL));

        validation.checkOwnerFieldsToCreate(entity);

        assertThat(entity.getAccountingFirm()).isSameAs(firm);
        assertThat(entity.getProfile()).isEqualTo(UserProfileEnum.EMPLOYEE);
        assertThat(entity.getAccessProfiles()).containsExactlyInAnyOrder(AccessProfile.FISCAL, AccessProfile.FINANCIAL);
    }

    @Test
    void theLastAdminCannotBeDemoted() {
        when(messageService.getMessage("error.user.last.admin")).thenReturn("last admin");
        UUID id = UUID.randomUUID();
        UUID firmId = UUID.randomUUID();
        User persisted = new User(id);
        persisted.setProfile(UserProfileEnum.ADMIN);
        persisted.setAccountingFirm(new AccountingFirm(firmId));
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));
        when(repository.countByAccountingFirm_IdAndProfileAndDeletedIsFalse(firmId, UserProfileEnum.ADMIN)).thenReturn(1L);
        User toUpdate = new User(id);
        toUpdate.setProfile(UserProfileEnum.EMPLOYEE);

        assertThatThrownBy(() -> validation.checkUpdateConsistence(id, toUpdate))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("last admin");
        verify(mapper, never()).merge(any(), any());
    }

    @Test
    void anAdminCanBeDemotedWhenAnotherOneRemains() {
        UUID id = UUID.randomUUID();
        UUID firmId = UUID.randomUUID();
        User persisted = new User(id);
        persisted.setProfile(UserProfileEnum.ADMIN);
        persisted.setAccountingFirm(new AccountingFirm(firmId));
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));
        when(repository.countByAccountingFirm_IdAndProfileAndDeletedIsFalse(firmId, UserProfileEnum.ADMIN)).thenReturn(2L);
        User toUpdate = new User(id);
        toUpdate.setProfile(UserProfileEnum.EMPLOYEE);

        validation.checkUpdateConsistence(id, toUpdate);

        verify(mapper).merge(toUpdate, persisted);
    }

    @Test
    void checkOwnerFieldsToCreateRequiresAPassword() {
        when(messageService.getMessage("error.user.password.required")).thenReturn("password required");
        User entity = new User();
        entity.setPassword(" ");

        assertThatThrownBy(() -> validation.checkOwnerFieldsToCreate(entity))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("password required");
    }

    @Test
    void checkUpdateConsistenceHashesANewPasswordAndAppliesTheNewDocument() {
        UUID id = UUID.randomUUID();
        User toUpdate = new User(id);
        toUpdate.setPassword("new-plain");
        Document requested = new Document("98765432100", DocumentTypeEnum.CPF);
        toUpdate.setDocument(requested);
        User persisted = new User(id);
        persisted.setPassword("old-hash");
        persisted.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        Document current = new Document("12345678909", DocumentTypeEnum.CPF);
        persisted.setDocument(current);
        Document updated = new Document("98765432100", DocumentTypeEnum.CPF);
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));
        when(passwordEncoder.encode("new-plain")).thenReturn("new-hash");
        when(documentValidation.updateNumberAndType(current, requested)).thenReturn(updated);

        User result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result.getPassword()).isEqualTo("new-hash");
        assertThat(result.getDocument()).isSameAs(updated);
    }

    @Test
    void checkUpdateConsistenceKeepsTheCurrentPasswordWhenNoneIsSent() {
        UUID id = UUID.randomUUID();
        User toUpdate = new User(id);
        User persisted = new User(id);
        persisted.setPassword("old-hash");
        persisted.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));

        User result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result.getPassword()).isEqualTo("old-hash");
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void checkUpdateConsistenceThrowsWhenIdsAreInconsistent() {
        when(messageService.getMessage("error.generic.bad.request")).thenReturn("bad request");
        User toUpdate = new User(UUID.randomUUID());

        assertThatThrownBy(() -> validation.checkUpdateConsistence(UUID.randomUUID(), toUpdate))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("bad request");
    }

    @Test
    void checkUpdateConsistenceMergesIntoPersistedEntity() {
        UUID id = UUID.randomUUID();
        User toUpdate = new User(id);
        User persisted = new User(id);
        persisted.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));

        User result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result).isSameAs(persisted);
        verify(mapper).merge(toUpdate, persisted);
    }

    @Test
    void checkDocumentCreatesANewDocumentWhenItHasNoId() {
        User entity = new User();
        Document newDocument = new Document("12345678909", DocumentTypeEnum.CPF);
        entity.setDocument(newDocument);
        Document saved = new Document("12345678909", DocumentTypeEnum.CPF);
        saved.setId(UUID.randomUUID());
        when(documentValidation.saveRelation(newDocument)).thenReturn(saved);

        validation.checkDocument(entity);

        verify(documentValidation).checkExistByNumberAndType(newDocument);
        assertThat(entity.getDocument()).isSameAs(saved);
    }

    @Test
    void checkDocumentResolvesAnExistingDocumentById() {
        User entity = new User();
        UUID documentId = UUID.randomUUID();
        Document reference = new Document();
        reference.setId(documentId);
        entity.setDocument(reference);
        Document resolved = new Document("12345678909", DocumentTypeEnum.CPF);
        resolved.setId(documentId);
        when(documentValidation.checkExist(documentId)).thenReturn(resolved);

        validation.checkDocument(entity);

        assertThat(entity.getDocument()).isSameAs(resolved);
    }

    @Test
    void checkValidationsExtrasThrowsWhenLoginAlreadyUsedByAnotherUser() {
        User entity = new User(UUID.randomUUID());
        entity.setLogin("jane");
        when(repository.existsByLoginAndIdNot("jane", entity.getId())).thenReturn(true);
        when(messageService.getMessage("error.user.login.already.exists")).thenReturn("login already exists");

        assertThatThrownBy(() -> validation.checkValidationsExtras(entity))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.login.already.exists"))
                .hasMessage("login already exists");
    }

    /** PUT /users: renaming to the login of a deleted user is a 409 with the code, not a 500 on uk_users_login. */
    @Test
    void renamingToTheLoginOfADeletedUserIsAConflict() {
        User entity = new User(UUID.randomUUID());
        entity.setLogin("excluido");
        when(repository.existsByLoginAndIdNot("excluido", entity.getId())).thenReturn(true);
        when(messageService.getMessage("error.user.login.already.exists")).thenReturn("login already exists");

        assertThatThrownBy(() -> validation.checkValidationsExtras(entity))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.login.already.exists"));
        verify(repository, never()).existsByLoginAndDeletedIsFalseAndIdNot(any(), any());
    }

    /** POST /users: a login of a deleted user is also taken (uk_users_login) - 409 with the code, not a database error. */
    @Test
    void aNewUserCannotTakeTheLoginOfADeletedOne() {
        User entity = new User();
        entity.setLogin("jane");
        when(repository.existsByLogin("jane")).thenReturn(true);
        when(messageService.getMessage("error.user.login.already.exists")).thenReturn("login already exists");

        assertThatThrownBy(() -> validation.checkValidationsExtras(entity))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.login.already.exists"));
    }

    /** POST /users: the CPF of another user - 409 error.user.document.already.exists, with the current message. */
    @Test
    void aTakenCpfIsAConflictWithCode() {
        when(messageService.getMessage("error.document.exist")).thenReturn("documento repetido");
        Document requested = new Document("12345678909", DocumentTypeEnum.CPF);
        org.mockito.Mockito.doThrow(new ConflictException("x")).when(documentValidation).checkExistByNumberAndType(requested);
        User entity = new User();
        entity.setDocument(requested);

        assertThatThrownBy(() -> validation.checkDocument(entity))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("error.user.document.already.exists");
                    assertThat(e.getMessage()).isEqualTo("documento repetido");
                });
    }

    /** Review item 4: a password over BCrypt's 72 bytes is a 400 with a code - never a 500 - on create and on update. */
    @Test
    void aPasswordOver72BytesIsRefused() {
        when(messageService.getMessage("error.user.password.invalid")).thenReturn("senha longa");
        User entity = new User();
        entity.setPassword("s".repeat(73));

        assertThatThrownBy(() -> validation.checkOwnerFieldsToCreate(entity))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.password.invalid"));
        verify(passwordEncoder, never()).encode(any());

        UUID id = UUID.randomUUID();
        User persisted = new User(id);
        persisted.setAccountingFirm(new AccountingFirm(UUID.randomUUID()));
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));
        User toUpdate = new User(id);
        toUpdate.setPassword("ç".repeat(37)); // 74 bytes in UTF-8
        assertThatThrownBy(() -> validation.checkUpdateConsistence(id, toUpdate))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void checkValidationsExtrasPassesWhenLoginIsFree() {
        User entity = new User(UUID.randomUUID());
        entity.setLogin("jane");
        

        validation.checkValidationsExtras(entity);
    }

    // ------------------------------------------------------------------ spec 2026-11-27, SN-RN-08 and SN-RN-10

    private User persistedTeammate(UUID id) {
        User persisted = new User(id);
        persisted.setPassword("old-hash");
        persisted.setLogin("bruno.ficticio");
        persisted.setSessionVersion(2);
        persisted.setAccountingFirm(adminFirm);
        when(repository.findFirstByIdAndDeletedIsFalse(id)).thenReturn(Optional.of(persisted));
        return persisted;
    }

    /** CT-SN-24: the ADMIN sets the teammate's password - sessions fall (version + 1) and the notice goes after the commit. */
    @Test
    void anAdminSettingATeammatesPasswordRevokesHisSessionsAndNotifiesHim() {
        UUID id = UUID.randomUUID();
        User persisted = persistedTeammate(id);
        User toUpdate = new User(id);
        toUpdate.setLogin("bruno.ficticio");
        toUpdate.setPassword("Senha2026x");
        when(passwordEncoder.encode("Senha2026x")).thenReturn("new-hash");

        User result = validation.checkUpdateConsistence(id, toUpdate);

        assertThat(result.getPassword()).isEqualTo("new-hash");
        assertThat(result.getSessionVersion()).isEqualTo(3);
        assertThat(result.getPasswordChangedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 11, 27, 10, 0));
        org.mockito.ArgumentCaptor<br.com.cshub.taxify.service.password.PasswordChangedEvent> event =
                org.mockito.ArgumentCaptor.forClass(br.com.cshub.taxify.service.password.PasswordChangedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(id);
        assertThat(event.getValue().channel()).isEqualTo(br.com.cshub.taxify.enumerated.PasswordChangeChannel.ADMIN);
        // review item 6: his open reset links stop working too
        verify(resetTokens).invalidateOpen(id, java.time.LocalDateTime.of(2026, 11, 27, 10, 0));
    }

    /** CT-SN-25: the password rule applies to the ADMIN too - "abc" is too short; nothing changes. */
    @Test
    void anAdminSettingATooShortPasswordGets400() {
        UUID id = UUID.randomUUID();
        User persisted = persistedTeammate(id);
        User toUpdate = new User(id);
        toUpdate.setLogin("bruno.ficticio");
        toUpdate.setPassword("abc");

        assertThatThrownBy(() -> validation.checkUpdateConsistence(id, toUpdate))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.password.too.short"));
        assertThat(persisted.getSessionVersion()).isEqualTo(2);
        verify(passwordEncoder, never()).encode(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** CT-SN-26: the ADMIN's own password only through PUT /users/me/password; the rest of his record stays editable. */
    @Test
    void anAdminCannotSetHisOwnPasswordThroughTheUsersScreen() {
        UUID ownId = currentUserService.getCurrentUser().orElseThrow().getId();
        persistedTeammate(ownId);
        User withPassword = new User(ownId);
        withPassword.setLogin("bruno.ficticio");
        withPassword.setPassword("OutraSenha2026");

        assertThatThrownBy(() -> validation.checkUpdateConsistence(ownId, withPassword))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.password.use.self.change"));

        User nameOnly = new User(ownId);
        nameOnly.setLogin("bruno.ficticio");
        nameOnly.setName("Nome Novo");
        assertThat(validation.checkUpdateConsistence(ownId, nameOnly).getPassword()).isEqualTo("old-hash");
    }

    /** SN-RN-08 (P-SN-10): a new login cannot have ":" - on create and when an edit changes the login. */
    @Test
    void aNewLoginWithAColonIsRefused() {
        User entity = new User();
        entity.setLogin("teste:ponto");
        entity.setPassword("plain-pass");
        assertThatThrownBy(() -> validation.checkOwnerFieldsToCreate(entity))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.login.invalid.char"));

        UUID id = UUID.randomUUID();
        persistedTeammate(id);
        User toUpdate = new User(id);
        toUpdate.setLogin("bruno:ficticio");
        assertThatThrownBy(() -> validation.checkUpdateConsistence(id, toUpdate))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.login.invalid.char"));
    }

    /** CT-SN-12 / CA-SN-07 for a new user: 7 characters is too short, 8 passes. */
    @Test
    void theMinimumLengthIsEightCharacters() {
        User shortOne = new User();
        shortOne.setLogin("ana.ficticia");
        shortOne.setPassword("Abc1234");
        assertThatThrownBy(() -> validation.checkOwnerFieldsToCreate(shortOne))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.password.too.short"));

        User okOne = new User();
        okOne.setLogin("ana.ficticia");
        okOne.setPassword("Abc12345");
        when(passwordEncoder.encode("Abc12345")).thenReturn("hash");
        validation.checkOwnerFieldsToCreate(okOne);
        assertThat(okOne.getPassword()).isEqualTo("hash");
    }

    /** Review item 7: the ADMIN cannot change his own login here (his token carries it); another ADMIN can. */
    @Test
    void anAdminCannotChangeHisOwnLogin() {
        UUID ownId = currentUserService.getCurrentUser().orElseThrow().getId();
        persistedTeammate(ownId);
        User renamed = new User(ownId);
        renamed.setLogin("carla.nova");
        assertThatThrownBy(() -> validation.checkUpdateConsistence(ownId, renamed))
                .isInstanceOfSatisfying(BadRequestException.class,
                        e -> assertThat(e.getCode()).isEqualTo("error.user.login.self.change"));

        UUID other = UUID.randomUUID();
        persistedTeammate(other);
        User teammate = new User(other);
        teammate.setLogin("bruno.novo");
        assertThat(validation.checkUpdateConsistence(other, teammate)).isNotNull();
    }
}
