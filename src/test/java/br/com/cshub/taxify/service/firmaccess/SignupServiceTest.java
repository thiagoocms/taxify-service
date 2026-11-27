package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.dto.signup.SignupConfigDTO;
import br.com.cshub.taxify.dto.signup.SignupFirmDTO;
import br.com.cshub.taxify.dto.signup.SignupOwnerDTO;
import br.com.cshub.taxify.dto.signup.SignupRequestDTO;
import br.com.cshub.taxify.dto.signup.SignupResponseDTO;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.PlatformAdminService;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.domain.firmaccess.TermsAcceptance;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.exception.BusinessException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.DocumentRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.TermsAcceptanceRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.validation.SignupValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F1-RN-01, F1-RN-02, F1-RN-04, F1-RN-06 e F1-RN-11 (dados fictícios). */
class SignupServiceTest {

    private static final String TERMS = "2026-12-01";
    private static final String IP = "198.51.100.7";

    private final SaasProperties properties = new SaasProperties();
    private final AccountingFirmRepository firms = mock(AccountingFirmRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final TermsAcceptanceRepository terms = mock(TermsAcceptanceRepository.class);
    private final FirmSubscriptionRepository subscriptions = mock(FirmSubscriptionRepository.class);
    private final FirmEventService events = mock(FirmEventService.class);
    private final EmailVerificationService verification = mock(EmailVerificationService.class);
    private final PlatformAdminService platformAdmin = mock(PlatformAdminService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final MessageService messages = mock(MessageService.class);
    private final MutableClock clock = new MutableClock(LocalDateTime.of(2026, 11, 26, 10, 0));
    private SignupService service;

    @BeforeEach
    void setUp() {
        properties.setSignupEnabled(true);
        properties.setTermsVersion(TERMS);
        SignupValidation validation = new SignupValidation(messages, properties, platformAdmin, users, documents, firms,
                new br.com.cshub.taxify.validation.PasswordPolicy(new br.com.cshub.taxify.config.PasswordProperties(), encoder, messages));
        service = new SignupService(properties, validation, firms, users, documents, terms, subscriptions, events, verification,
                encoder, messages, clock, mock(PlatformTransactionManager.class));
        lenient().when(messages.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(messages.getMessage(anyString(), any(String[].class))).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(encoder.encode(anyString())).thenReturn("hash");
        lenient().when(firms.saveAndFlush(any(AccountingFirm.class))).thenAnswer(invocation -> {
            AccountingFirm firm = invocation.getArgument(0);
            firm.setId(UUID.randomUUID());
            return firm;
        });
        lenient().when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });
        lenient().when(verification.issue(any(), eq(false))).thenReturn("token-ficticio");
    }

    private static SignupRequestDTO request(String cnpj, String phone, String login, String termsVersion) {
        return new SignupRequestDTO(
                new SignupFirmDTO("Escritório Fictício", cnpj, "financeiro@ficticio.test", phone),
                new SignupOwnerDTO("Dona Fictícia", "123.456.789-09", login, "dona@ficticio.test", "segredo-de-teste"),
                termsVersion);
    }

    private static SignupRequestDTO valid() {
        return request("11.222.333/0001-81", "(81) 9 9999-0000", "dona.ficticia", TERMS);
    }

    private static String code(Throwable e) {
        return ((BusinessException) e).getCode();
    }

    /** CT1-01 / CT1-06 / CT1-09 / CA1-14: ADMIN sem perfis, telefone só dígitos, aceite com versão, data e IP, PENDING_VERIFICATION. */
    @Test
    void aValidSignupCreatesThePendingFirmAndItsAdmin() {
        SignupResponseDTO response = service.signup(valid(), IP);

        assertThat(response.status()).isEqualTo("PENDING_VERIFICATION");
        assertThat(response.verificationHours()).isEqualTo(24);
        ArgumentCaptor<AccountingFirm> firm = ArgumentCaptor.forClass(AccountingFirm.class);
        verify(firms).saveAndFlush(firm.capture());
        assertThat(firm.getValue().getDocumentNumber()).isEqualTo("11222333000181");
        assertThat(firm.getValue().getContactPhone()).isEqualTo("81999990000");
        assertThat(firm.getValue().getBillingEmail()).isEqualTo("financeiro@ficticio.test");

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(user.capture());
        assertThat(user.getValue().getProfile()).isEqualTo(UserProfileEnum.ADMIN);
        assertThat(user.getValue().getAccessProfiles()).isEmpty();
        assertThat(user.getValue().getDocument().getNumber()).isEqualTo("12345678909");

        ArgumentCaptor<TermsAcceptance> acceptance = ArgumentCaptor.forClass(TermsAcceptance.class);
        verify(terms).save(acceptance.capture());
        assertThat(acceptance.getValue().getTermsVersion()).isEqualTo(TERMS);
        assertThat(acceptance.getValue().getIp()).isEqualTo(IP);
        assertThat(acceptance.getValue().getAcceptedAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));

        ArgumentCaptor<FirmSubscription> subscription = ArgumentCaptor.forClass(FirmSubscription.class);
        verify(subscriptions).save(subscription.capture());
        assertThat(subscription.getValue().getStatus()).isEqualTo(FirmSubscriptionStatus.PENDING_VERIFICATION);
        verify(verification).sendVerification("dona@ficticio.test", "Escritório Fictício", "token-ficticio");
    }

    /** CA1-13 / CT1-23: fechado (chave desligada ou termos RASCUNHO) → 503 error.signup.unavailable. */
    @Test
    void closedSignupAnswers503() {
        properties.setSignupEnabled(false);
        assertThatThrownBy(() -> service.signup(valid(), IP)).satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.unavailable"));
        properties.setSignupEnabled(true);
        properties.setTermsVersion("RASCUNHO-1");
        assertThatThrownBy(() -> service.signup(valid(), IP)).satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.unavailable"));
        assertThat(service.config().available()).isFalse();
    }

    /** CA1-14 / CT1-24: sem versão 400; versão antiga 409. */
    @Test
    void termsMustBeTheCurrentVersion() {
        assertThatThrownBy(() -> service.signup(request("11222333000181", "81999990000", "x", null), IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.terms.required"));
        assertThatThrownBy(() -> service.signup(request("11222333000181", "81999990000", "x", "2026-01-01"), IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.terms.outdated"));
    }

    /** CA1-04 / CA1-05 / CT1-08 / CT1-09: CNPJ com DV errado e telefone sem DDD → 400. */
    @Test
    void invalidCnpjAndPhoneAreRejected() {
        assertThatThrownBy(() -> service.signup(request("11.111.111/0001-11", "81999990000", "x", TERMS), IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.firm.document.invalid"));
        assertThatThrownBy(() -> service.signup(request("11222333000181", "9999-0000", "x", TERMS), IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.phone.invalid"));
    }

    /** CA1-04 / CT1-08: CNPJ de outro escritório → 409; a unicidade é só entre escritórios (accounting_firms). */
    @Test
    void cnpjOfAnotherFirmIsAConflict() {
        when(firms.existsByDocumentNumber("11222333000181")).thenReturn(true);
        assertThatThrownBy(() -> service.signup(valid(), IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.firm.document.exists"));
        // the client-company CNPJ check (documents table, type CNPJ) is never asked
        verify(documents, never()).existsByNumberAndTypeNative(eq("11222333000181"), any());
    }

    /** CA1-06 / CT1-11: login da lista da CS Hub = a mesma resposta de login em uso. */
    @Test
    void reservedLoginLooksLikeALoginInUse() {
        when(platformAdmin.isReservedLogin("csadmin")).thenReturn(true);
        assertThatThrownBy(() -> service.signup(request("11222333000181", "81999990000", "csadmin", TERMS), IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.user.login.already.exists"))
                .hasMessage("error.user.login.already.exists");
        when(users.existsByLogin("ja.usado")).thenReturn(true);
        assertThatThrownBy(() -> service.signup(request("11222333000181", "81999990000", "ja.usado", TERMS), IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.user.login.already.exists"))
                .hasMessage("error.user.login.already.exists");
    }

    /** CPF já cadastrado: 409 error.user.document.already.exists, com a mensagem de documento repetido. */
    @Test
    void takenCpfIsAConflictWithCode() {
        when(documents.existsByNumberAndTypeNative("12345678909", br.com.cshub.taxify.enumerated.DocumentTypeEnum.CPF)).thenReturn(true);
        assertThatThrownBy(() -> service.signup(valid(), IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.user.document.already.exists"))
                .hasMessage("error.document.exist");
    }

    /** CA1-07 / CT1-13: o 4º cadastro do mesmo IP no mesmo dia (Recife) → 429; o dia é contado de 00:00 a 00:00. */
    @Test
    void fourthSignupFromTheSameIpTheSameDayIsRateLimited() {
        when(terms.countByIpAndAcceptedAtGreaterThanEqualAndAcceptedAtLessThanAndDeletedIsFalse(IP,
                LocalDateTime.of(2026, 11, 26, 0, 0), LocalDateTime.of(2026, 11, 27, 0, 0))).thenReturn(3L);
        assertThatThrownBy(() -> service.signup(valid(), IP)).satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.rate.limited"));

        clock.set(LocalDateTime.of(2026, 11, 27, 8, 0));
        assertThat(service.signup(valid(), IP).status()).isEqualTo("PENDING_VERIFICATION");
    }

    /** CT1-10: o e-mail financeiro pode ser igual ao do usuário. */
    @Test
    void billingEmailMayEqualTheUserEmail() {
        SignupRequestDTO same = new SignupRequestDTO(
                new SignupFirmDTO("Escritório Fictício", "11222333000181", "dona@ficticio.test", "81999990000"),
                new SignupOwnerDTO("Dona Fictícia", "12345678909", "dona.ficticia", "dona@ficticio.test", "segredo-de-teste"),
                TERMS);
        assertThat(service.signup(same, IP).status()).isEqualTo("PENDING_VERIFICATION");
    }

    /** Review item 5: the column limits are checked up front - never a database error turned into "already exists". */
    @Test
    void columnLimitsAreValidated() {
        SignupRequestDTO longName = new SignupRequestDTO(
                new SignupFirmDTO("Escritório Fictício", "11222333000181", "fin@ficticio.test", "81999990000"),
                new SignupOwnerDTO("N".repeat(151), "12345678909", "dona.ficticia", "dona@ficticio.test", "segredo"), TERMS);
        assertThatThrownBy(() -> service.signup(longName, IP)).satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.user.name.invalid"));

        SignupRequestDTO longPassword = new SignupRequestDTO(
                new SignupFirmDTO("Escritório Fictício", "11222333000181", "fin@ficticio.test", "81999990000"),
                new SignupOwnerDTO("Dona", "12345678909", "dona.ficticia", "dona@ficticio.test", "s".repeat(73)), TERMS);
        assertThatThrownBy(() -> service.signup(longPassword, IP)).satisfies(e -> assertThat(code(e)).isEqualTo("error.signup.password.invalid"));
    }

    /** Review item 5: only a unique-key violation (MySQL 1062) is the "already exists" conflict; any other goes on as is. */
    @Test
    void onlyADuplicateKeyIsAConflict() {
        org.springframework.dao.DataIntegrityViolationException duplicate = new org.springframework.dao.DataIntegrityViolationException(
                "dup", new java.sql.SQLIntegrityConstraintViolationException("Duplicate entry", "23000", 1062));
        org.springframework.dao.DataIntegrityViolationException tooLong = new org.springframework.dao.DataIntegrityViolationException(
                "too long", new java.sql.SQLException("Data too long for column", "22001", 1406));

        when(firms.saveAndFlush(any(AccountingFirm.class))).thenThrow(duplicate);
        assertThatThrownBy(() -> service.signup(valid(), IP)).isInstanceOf(ConflictException.class).hasMessage("error.signup.conflict");

        org.mockito.Mockito.reset(firms);
        when(firms.saveAndFlush(any(AccountingFirm.class))).thenThrow(tooLong);
        assertThatThrownBy(() -> service.signup(valid(), IP)).isSameAs(tooLong);

        assertThat(br.com.cshub.taxify.common.DuplicateKeys.isDuplicateKey(new org.springframework.dao.DuplicateKeyException("dup"))).isTrue();
    }

    @Test
    void configShowsTheTermsOnlyWhenOpen() {
        properties.setTermsUrl("https://exemplo.test/termos");
        SignupConfigDTO config = service.config();
        assertThat(config.available()).isTrue();
        assertThat(config.termsVersion()).isEqualTo(TERMS);
        assertThat(config.termsUrl()).isEqualTo("https://exemplo.test/termos");
        assertThat(config.trialDays()).isEqualTo(14);
        assertThat(List.of(config.privacyUrl() == null, config.dpaUrl() == null)).containsOnly(true);
    }

    /** Spec 2026-11-27, SN-RN-08: a new login with ":" and a password under 8 characters are refused at signup. */
    @Test
    void theSignupAppliesTheNewPasswordAndLoginRules() {
        assertThatThrownBy(() -> service.signup(request("11.222.333/0001-81", "(81) 9 9999-0000", "dona:ficticia", TERMS), IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.user.login.invalid.char"));

        SignupRequestDTO shortPassword = new SignupRequestDTO(
                new SignupFirmDTO("Escritório Fictício", "11222333000181", "fin@ficticio.test", "81999990000"),
                new SignupOwnerDTO("Dona", "12345678909", "dona.ficticia", "dona@ficticio.test", "Abc1234"), TERMS);
        assertThatThrownBy(() -> service.signup(shortPassword, IP))
                .satisfies(e -> assertThat(code(e)).isEqualTo("error.user.password.too.short"));
    }
}
