package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.domain.firmaccess.FirmSubscriptionSnapshot;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.firmaccess.EmailVerification;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.signup.SignupVerifyResponseDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.mail.MailSendException;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.EmailVerificationRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Verificação do e-mail do cadastro (F1-RN-03, F1-RN-05).
 * <ul>
 *   <li>Token de 32 bytes aleatórios (base64url); só o SHA-256 é gravado. Vale uma vez e por
 *       {@code TAXIFY_EMAIL_VERIFICATION_HOURS} horas contadas da emissão.</li>
 *   <li>Verificado: TRIAL até {@code hoje + TAXIFY_TRIAL_DAYS − 1} (o dia da verificação é o dia 1), com os eventos
 *       EMAIL_VERIFIED e TRIAL_STARTED.</li>
 *   <li>Reenvio: sempre 202, exista ou não o login; no máximo {@code TAXIFY_EMAIL_RESEND_PER_HOUR} nos últimos
 *       60 minutos (o e-mail do cadastro não conta); cada reenvio invalida o link anterior.</li>
 *   <li>Teto diário (decisão do Tony): no máximo {@code TAXIFY_EMAIL_RESEND_PER_DAY} reenvios por login e
 *       {@code TAXIFY_EMAIL_RESEND_PER_IP_PER_DAY} por IP no dia civil de Recife. Acima dele também é 202 sem envio,
 *       e o log só registra {@code signup.resend.limited}, o mesmo nome do limite por hora (F1-RN-03).</li>
 * </ul>
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final EmailVerificationRepository verificationRepository;
    private final FirmSubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final FirmEventService eventService;
    private final MailSender mailSender;
    private final SaasProperties properties;
    private final MessageService messageService;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public EmailVerificationService(EmailVerificationRepository verificationRepository, FirmSubscriptionRepository subscriptionRepository,
                                    UserRepository userRepository, FirmEventService eventService, MailSender mailSender,
                                    SaasProperties properties, MessageService messageService, Clock clock,
                                    PlatformTransactionManager transactionManager) {
        this.verificationRepository = verificationRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.userRepository = userRepository;
        this.eventService = eventService;
        this.mailSender = mailSender;
        this.properties = properties;
        this.messageService = messageService;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Emite um link novo para o usuário e devolve o token em claro (só para ir no e-mail). Dentro da transação de quem chama. */
    @Transactional
    public String issue(UUID userId, boolean resend) {
        return issue(userId, resend, null);
    }

    private String issue(UUID userId, boolean resend, String requestedIp) {
        String token = newToken();
        LocalDateTime now = LocalDateTime.now(clock);
        EmailVerification verification = new EmailVerification(userId, hash(token), now,
                now.plusHours(properties.getEmailVerificationHours()), resend);
        verification.setRequestedIp(requestedIp);
        verificationRepository.save(verification);
        return token;
    }

    /** Envia o link; uma falha fica registrada (sem o token) e o usuário pode pedir o reenvio. */
    public void sendVerification(String email, String firmName, String token) {
        try {
            mailSender.send(FirmMailTemplates.verification(List.of(email), firmName,
                    FirmMailTemplates.verificationLink(properties.getPublicUrl(), token), properties.getEmailVerificationHours()));
        } catch (MailSendException e) {
            log.warn("signup.mail.failed - verification e-mail not sent: {}", e.getMessage());
        } catch (RuntimeException e) {
            // other failures: only the class - the text could carry the address
            log.warn("signup.mail.failed - verification e-mail not sent: {}", e.getClass().getSimpleName());
        }
    }

    /** POST /signup/verify: o teste começa; token vencido, usado ou desconhecido = 400 {@code error.signup.token.invalid}. */
    public SignupVerifyResponseDTO verify(String token) {
        return transaction.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            EmailVerification verification = (token == null || token.isBlank()) ? null
                    : verificationRepository.findByTokenHash(hash(token.strip())).orElse(null);
            if (verification == null || !verification.isUsableAt(now)) {
                throw invalidToken();
            }
            User user = userRepository.findById(verification.getUserId()).orElseThrow(this::invalidToken);
            UUID firmId = user.getAccountingFirm().getId();
            // locks the same row as the purge (UnverifiedSignupPurge): a verification and a purge never cross
            FirmSubscription subscription = subscriptionRepository.findForUpdate(firmId)
                    .filter(found -> found.getStatus() == FirmSubscriptionStatus.PENDING_VERIFICATION)
                    .orElseThrow(this::invalidToken);
            // the token is used once: two clicks at the same time - only one marks it
            if (verificationRepository.markUsed(verification.getId(), now) == 0) {
                throw invalidToken();
            }
            // LGPD: the resend IPs were only for the daily cap of an unverified signup
            verificationRepository.clearRequestedIp(user.getId());

            FirmSubscriptionSnapshot before = subscription.snapshot();
            LocalDate today = now.toLocalDate();
            subscription.setStatus(FirmSubscriptionStatus.TRIAL);
            subscription.setTrialEndsOn(FirmAccessRules.trialEndsOn(today, properties.getTrialDays()));
            subscriptionRepository.save(subscription);
            eventService.record(firmId, FirmEventType.EMAIL_VERIFIED, before, before, null, FirmEventService.SYSTEM);
            eventService.record(firmId, FirmEventType.TRIAL_STARTED, before, subscription.snapshot(), null, FirmEventService.SYSTEM);
            return new SignupVerifyResponseDTO(FirmSubscriptionStatus.TRIAL.name(), subscription.getTrialEndsOn());
        });
    }

    /**
     * POST /signup/resend: sempre 202 para quem chama, exista ou não o login (não revela contas). Só um cadastro
     * ainda pendente recebe o link novo, e dentro do limite por hora.
     */
    public void resend(String login) {
        resend(login, null);
    }

    /** @param ip de quem pediu (ClientIpResolver) - conta no teto diário por IP */
    public void resend(String login, String ip) {
        if (login == null || login.isBlank()) {
            return;
        }
        record Pending(String email, String firmName, String token) {
        }
        Pending pending = transaction.execute(status -> {
            User user = userRepository.findByLoginAndDeletedIsFalse(login.strip());
            if (user == null) {
                return null;
            }
            boolean isPending = subscriptionRepository.findByAccountingFirmIdAndDeletedIsFalse(user.getAccountingFirm().getId())
                    .map(subscription -> subscription.getStatus() == FirmSubscriptionStatus.PENDING_VERIFICATION)
                    .orElse(false);
            if (!isPending) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now(clock);
            long recent = verificationRepository.countByUserIdAndResendIsTrueAndCreatedDateAfter(user.getId(), now.minusMinutes(60));
            if (recent >= properties.getEmailResendPerHour()) {
                log.info("signup.resend.limited");
                return null;
            }
            if (dailyCapReached(user.getId(), ip, now)) {
                log.info("signup.resend.limited");
                return null;
            }
            verificationRepository.invalidateOpen(user.getId(), now);
            String token = issue(user.getId(), true, ip);
            return new Pending(user.getEmail(), user.getAccountingFirm().getName(), token);
        });
        if (pending != null) {
            sendVerification(pending.email(), pending.firmName(), pending.token());
        }
    }

    /** Teto do dia civil de Recife: por login e, quando o IP é conhecido, por IP. */
    private boolean dailyCapReached(UUID userId, String ip, LocalDateTime now) {
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        long byLogin = verificationRepository.countByUserIdAndResendIsTrueAndCreatedDateGreaterThanEqualAndCreatedDateLessThan(
                userId, dayStart, dayEnd);
        if (byLogin >= properties.getEmailResendPerDay()) {
            return true;
        }
        return ip != null && verificationRepository
                .countByRequestedIpAndResendIsTrueAndCreatedDateGreaterThanEqualAndCreatedDateLessThan(ip, dayStart, dayEnd)
                >= properties.getEmailResendPerIpPerDay();
    }

    private BadRequestException invalidToken() {
        return new BadRequestException("error.signup.token.invalid", messageService.getMessage("error.signup.token.invalid"));
    }

    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
