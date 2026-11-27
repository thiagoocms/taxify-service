package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.common.DuplicateKeys;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.domain.firmaccess.TermsAcceptance;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.signup.SignupConfigDTO;
import br.com.cshub.taxify.dto.signup.SignupRequestDTO;
import br.com.cshub.taxify.dto.signup.SignupResponseDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.enumerated.SubscriptionSource;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.exception.ServiceUnavailableException;
import br.com.cshub.taxify.exception.TooManyRequestsException;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.DocumentRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.TermsAcceptanceRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.validation.SignupValidation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Cadastro público do escritório (F1-RN-01, F1-RN-02, F1-RN-04, F1-RN-06, F1-RN-11). Cria o escritório, o ADMIN
 * (sempre ADMIN, sem perfis - o pedido não tem campo de perfil), o aceite dos termos com o IP e a assinatura em
 * PENDING_VERIFICATION, e manda o link de verificação. O teste só começa no clique (EmailVerificationService). As
 * validações de entrada ficam em {@link SignupValidation}.
 */
@Service
public class SignupService {

    static final String SIGNUP_ACTOR = "signup";

    private final SaasProperties properties;
    private final SignupValidation validation;
    private final AccountingFirmRepository firmRepository;
    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final TermsAcceptanceRepository termsRepository;
    private final FirmSubscriptionRepository subscriptionRepository;
    private final FirmEventService eventService;
    private final EmailVerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final MessageService messageService;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SignupService(SaasProperties properties, SignupValidation validation, AccountingFirmRepository firmRepository,
                         UserRepository userRepository, DocumentRepository documentRepository,
                         TermsAcceptanceRepository termsRepository, FirmSubscriptionRepository subscriptionRepository,
                         FirmEventService eventService, EmailVerificationService verificationService,
                         PasswordEncoder passwordEncoder, MessageService messageService, Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.validation = validation;
        this.firmRepository = firmRepository;
        this.userRepository = userRepository;
        this.documentRepository = documentRepository;
        this.termsRepository = termsRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.eventService = eventService;
        this.verificationService = verificationService;
        this.passwordEncoder = passwordEncoder;
        this.messageService = messageService;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public boolean isAvailable() {
        return SignupRules.signupAvailable(properties.isSignupEnabled(), properties.getTermsVersion());
    }

    public SignupConfigDTO config() {
        boolean available = isAvailable();
        return new SignupConfigDTO(available, available ? properties.getTermsVersion() : null,
                blankToNull(properties.getTermsUrl()), blankToNull(properties.getPrivacyUrl()), blankToNull(properties.getDpaUrl()),
                properties.getTrialDays(), properties.getEmailVerificationHours());
    }

    public SignupResponseDTO signup(SignupRequestDTO request, String ip) {
        // F1-RN-11: trava de produção
        if (!isAvailable()) {
            throw new ServiceUnavailableException("error.signup.unavailable", messageService.getMessage("error.signup.unavailable"));
        }
        String terms = validation.checkRequest(request);
        SignupValidation.SignupData data = validation.checkFields(request);

        // F1-RN-06: limite por IP por dia civil de Recife, contado nos aceites gravados (não some no restart)
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        long fromIp = termsRepository.countByIpAndAcceptedAtGreaterThanEqualAndAcceptedAtLessThanAndDeletedIsFalse(ip,
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        if (fromIp >= properties.getSignupMaxPerIpPerDay()) {
            throw new TooManyRequestsException("error.signup.rate.limited", messageService.getMessage("error.signup.rate.limited"));
        }

        validation.checkRelations(data);

        Created created;
        try {
            created = transaction.execute(status -> create(data, terms, ip, now));
        } catch (DataIntegrityViolationException e) {
            // só a corrida com outro cadastro igual (login, CPF ou CNPJ) vira conflito: as chaves únicas seguraram.
            // Qualquer outra violação é erro do sistema e segue como tal.
            if (DuplicateKeys.isDuplicateKey(e)) {
                throw new ConflictException("error.signup.conflict", messageService.getMessage("error.signup.conflict"));
            }
            throw e;
        }
        verificationService.sendVerification(data.email(), data.firmName(), created.token());
        return new SignupResponseDTO(FirmSubscriptionStatus.PENDING_VERIFICATION.name(),
                properties.getEmailVerificationHours());
    }

    private record Created(AccountingFirm firm, User user, String token) {
    }

    private Created create(SignupValidation.SignupData data, String terms, String ip, LocalDateTime now) {
        AccountingFirm firm = new AccountingFirm();
        firm.setName(data.firmName());
        firm.setDocumentNumber(data.cnpj());
        firm.setBillingEmail(data.billingEmail());
        firm.setContactPhone(data.phone());
        firm.setDeleted(false);
        firmRepository.saveAndFlush(firm);

        Document document = new Document(data.cpf(), DocumentTypeEnum.CPF);
        document.setDeleted(false);
        documentRepository.save(document);

        User user = new User();
        user.setAccountingFirm(firm);
        user.setName(data.userName());
        user.setDocument(document);
        user.setLogin(data.login());
        user.setEmail(data.email());
        user.setPassword(passwordEncoder.encode(data.password()));
        // F1-RN-01: sempre ADMIN, sem perfis - o pedido não escolhe
        user.setProfile(UserProfileEnum.ADMIN);
        user.setAccessProfiles(null);
        user.setDeleted(false);
        userRepository.saveAndFlush(user);

        termsRepository.save(new TermsAcceptance(user.getId(), firm.getId(), terms, now, ip));

        FirmSubscription subscription = new FirmSubscription();
        subscription.setAccountingFirmId(firm.getId());
        subscription.setStatus(FirmSubscriptionStatus.PENDING_VERIFICATION);
        subscription.setSource(SubscriptionSource.MANUAL);
        subscription.setMigrated(false);
        subscription.setDeleted(false);
        subscriptionRepository.save(subscription);
        eventService.record(firm.getId(), FirmEventType.SIGNUP, null, subscription.snapshot(), null, SIGNUP_ACTOR);

        String token = verificationService.issue(user.getId(), false);
        return new Created(firm, user, token);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
