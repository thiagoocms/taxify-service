package br.com.cshub.taxify.service.password;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.NamedLocks;
import br.com.cshub.taxify.config.PasswordAsyncConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import br.com.cshub.taxify.common.OneTimeTokens;
import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.password.PasswordResetToken;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.password.PasswordConfigDTO;
import br.com.cshub.taxify.enumerated.PasswordChangeChannel;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ServiceUnavailableException;
import br.com.cshub.taxify.integration.mail.MailSendException;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.PasswordResetTokenRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.validation.PasswordPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * "Esqueci a senha" (spec 2026-11-27, SN-RN-01 a SN-RN-07 e SN-RN-14).
 * <ul>
 *   <li>Pedido anônimo pelo login ou pelo e-mail: a resposta é sempre 202, e a busca, a gravação e o envio rodam
 *       <b>depois</b> da resposta, numa thread virtual - o tempo de resposta não revela se a conta existe.</li>
 *   <li>Até 5 contas por pedido (as mais recentes); recebe a conta não excluída de escritório não excluído e fora de
 *       PENDING_VERIFICATION (inclusive bloqueado, suspenso, cancelado e sem assinatura). Um e-mail por conta.</li>
 *   <li>Token de 32 bytes em base64url, só o SHA-256 gravado; vale uma vez e por {@code TAXIFY_PASSWORD_RESET_MINUTES};
 *       um pedido novo invalida os links abertos da conta.</li>
 *   <li>Limites (o primeiro que estourar impede o envio, sempre com 202): por conta na última hora, por conta no dia
 *       civil de Recife e por IP no dia civil de Recife - contados em password_reset_tokens (sobrevivem a restart).</li>
 *   <li>Redefinição: na mesma transação marca o token (atômico), grava a senha, sobe a versão de sessão (as sessões
 *       caem), invalida os outros links e apaga os IPs; o aviso sai depois do commit (PasswordChangeNotifier).</li>
 * </ul>
 * Logs só com o id do usuário e o motivo - nunca login, e-mail, IP ou token (SN-RN-13). As rotas são anônimas: o
 * usuário é buscado direto no repositório, nunca pelo TenantAccessService.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    static final String TOKEN_INVALID = "error.password.reset.token.invalid";
    static final String UNAVAILABLE = "error.password.reset.unavailable";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final FirmAccessService firmAccessService;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final MailSender mailSender;
    private final PasswordProperties properties;
    private final SaasProperties saasProperties;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transaction;
    /** Where the request is processed after the 202 - bounded (PasswordAsyncConfig); unit tests run it inline. */
    private final Executor executor;
    /** Requests dropped because the executor was full - only the count goes to the log. */
    private final AtomicLong dropped = new AtomicLong();
    static final String IP_LOCK_PREFIX = "pwreset:";
    static final int IP_LOCK_SECONDS = 10;

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository tokenRepository,
                                FirmAccessService firmAccessService, PasswordPolicy passwordPolicy,
                                PasswordEncoder passwordEncoder, MailSender mailSender, PasswordProperties properties,
                                SaasProperties saasProperties, MessageService messageService,
                                ApplicationEventPublisher eventPublisher, Clock clock,
                                PlatformTransactionManager transactionManager,
                                @Qualifier(PasswordAsyncConfig.EXECUTOR) Executor executor) {
        this.executor = executor;
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.firmAccessService = firmAccessService;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.properties = properties;
        this.saasProperties = saasProperties;
        this.messageService = messageService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** SN-RN-14. */
    public PasswordConfigDTO config() {
        return new PasswordConfigDTO(properties.isResetEnabled(), properties.getResetMinutes(), passwordPolicy.minLength());
    }

    /**
     * {@code POST /auth/password/forgot}: só agenda o processamento e volta - quem chama responde 202 sem corpo, exista
     * ou não a conta. Desligado: 503 {@code error.password.reset.unavailable}.
     *
     * @param ip de quem pediu (ClientIpResolver), resolvido antes da resposta
     */
    public void requestReset(String identifier, String ip) {
        assertAvailable();
        if (identifier == null || identifier.isBlank()) {
            return;
        }
        String value = identifier.strip();
        try {
            executor.execute(() -> {
                try {
                    process(value, ip);
                } catch (RuntimeException e) {
                    log.warn("password.reset.failed error={}", e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException e) {
            // full (TAXIFY_PASSWORD_ASYNC_*): dropped, still 202 - nothing about the request in the log, only the count
            log.warn("password.reset.dropped total={}", dropped.incrementAndGet());
        }
    }

    private record Outgoing(UUID userId, String email, String login, String firmName, String token) {
    }

    /** Busca as contas, aplica as regras de quem recebe e os limites, emite e envia - uma conta não para as outras. */
    void process(String identifier, String ip) {
        List<UUID> accounts = transaction.execute(status -> findAccounts(identifier));
        if (accounts == null || accounts.isEmpty()) {
            return;
        }
        for (UUID userId : accounts) {
            Outgoing outgoing;
            try {
                outgoing = transaction.execute(status -> issueFor(userId, ip));
            } catch (RuntimeException e) {
                log.warn("password.reset.failed user={} error={}", userId, e.getClass().getSimpleName());
                continue;
            }
            if (outgoing != null) {
                send(outgoing);
            }
        }
    }

    /** O login igual, mais os e-mails iguais sem diferenciar maiúsculas, entre os não excluídos; as 5 mais recentes. */
    private List<UUID> findAccounts(String identifier) {
        Map<UUID, User> found = new LinkedHashMap<>();
        User byLogin = userRepository.findByLoginAndDeletedIsFalse(identifier);
        if (byLogin != null) {
            found.put(byLogin.getId(), byLogin);
        }
        if (identifier.contains("@")) {
            for (User user : userRepository.findByEmailForPasswordReset(identifier)) {
                found.putIfAbsent(user.getId(), user);
            }
        }
        List<User> accounts = new ArrayList<>(found.values());
        int max = properties.getResetMaxAccounts();
        if (accounts.size() > max) {
            log.info("password.reset.too.many.accounts count={}", accounts.size());
            accounts.sort(Comparator.comparing(User::getCreatedDate, Comparator.nullsLast(Comparator.reverseOrder())));
            accounts = accounts.subList(0, max);
        }
        return accounts.stream().map(User::getId).toList();
    }

    /** Uma conta, com a linha do usuário travada: as regras de quem recebe, os limites e a emissão do link. */
    private Outgoing issueFor(UUID userId, String ip) {
        // the cap per IP adds up every account: count and insert one at a time per IP, across instances (the row lock
        // below is only for this account). Taken first, before any read; released after the commit (NamedLocks).
        NamedLocks.Held ipLock = null;
        if (ip != null && !ip.isBlank()) {
            String name = NamedLocks.name(IP_LOCK_PREFIX, ip);
            ipLock = NamedLocks.untilTransactionEnds(() -> tokenRepository.getLock(name, IP_LOCK_SECONDS),
                    () -> releaseQuietly(name));
            if (ipLock == null) {
                log.info("password.reset.busy");
                return null;
            }
        }
        try (NamedLocks.Held held = ipLock) {
            return issueLocked(userId, ip);
        }
    }

    private Outgoing issueLocked(UUID userId, String ip) {
        User user = userRepository.findForUpdate(userId).orElse(null);
        if (user == null || user.isDeleted()) {
            return null;
        }
        AccountingFirm firm = user.getAccountingFirm();
        if (firm == null || firm.isDeleted()) {
            return null;
        }
        // P-SN-06: the unverified signup has no login yet - its way is "Reenviar e-mail de verificação"
        if (firmAccessService.accessOf(firm.getId()).pendingVerification()) {
            log.info("password.reset.skipped reason=PENDING_VERIFICATION");
            return null;
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            log.info("password.reset.skipped reason=NO_EMAIL user={}", user.getId());
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (limitReached(user.getId(), ip, now)) {
            log.info("password.reset.limited");
            return null;
        }
        tokenRepository.invalidateOpen(user.getId(), now);
        String token = OneTimeTokens.newToken();
        tokenRepository.save(new PasswordResetToken(user.getId(), OneTimeTokens.hash(token), now,
                now.plusMinutes(properties.getResetMinutes()), ip));
        return new Outgoing(user.getId(), user.getEmail().strip(), user.getLogin(), firm.getName(), token);
    }

    private void releaseQuietly(String name) {
        try {
            tokenRepository.releaseLock(name);
        } catch (RuntimeException e) {
            log.warn("password.reset.lock.release.failed error={}", e.getClass().getSimpleName());
        }
    }

    /** SN-RN-04: por conta na última hora, por conta no dia de Recife e, quando o IP é conhecido, por IP no dia. */
    private boolean limitReached(UUID userId, String ip, LocalDateTime now) {
        if (tokenRepository.countByUserIdAndRequestedAtGreaterThanEqual(userId, now.minusMinutes(60)) >= properties.getResetPerHour()) {
            return true;
        }
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        if (tokenRepository.countByUserIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(userId, dayStart, dayEnd)
                >= properties.getResetPerDay()) {
            return true;
        }
        if (ip == null || ip.isBlank()) {
            return false;
        }
        Long fromIp = tokenRepository.countByIpLocking(ip, dayStart, dayEnd);
        return fromIp != null && fromIp >= properties.getResetPerIpPerDay();
    }

    private void send(Outgoing outgoing) {
        try {
            mailSender.send(PasswordMailTemplates.reset(outgoing.email(), outgoing.login(), outgoing.firmName(),
                    PasswordMailTemplates.resetLink(saasProperties.getPublicUrl(), outgoing.token()), properties.getResetMinutes()));
            log.info("password.reset.sent user={}", outgoing.userId());
        } catch (MailSendException e) {
            log.warn("password.reset.mail.failed user={} {}", outgoing.userId(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("password.reset.mail.failed user={} error={}", outgoing.userId(), e.getClass().getSimpleName());
        }
    }

    /** {@code POST /auth/password/reset/check} (SN-RN-05): vale ou 400; não consome o token. */
    public void check(String token) {
        assertAvailable();
        transaction.execute(status -> {
            PasswordResetToken found = usableToken(token, LocalDateTime.now(clock));
            usableUser(userRepository.findById(found.getUserId()).orElse(null));
            return null;
        });
    }

    /**
     * {@code POST /auth/password/reset} (SN-RN-06). A regra de senha vem antes de consumir o token: senha recusada (400
     * com o código da regra) deixa o link valendo.
     */
    public void reset(String token, String newPassword) {
        assertAvailable();
        transaction.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            PasswordResetToken found = usableToken(token, now);
            User user = userRepository.findForUpdate(found.getUserId()).orElseThrow(this::invalidToken);
            usableUser(user);
            passwordPolicy.assertAcceptableChange(newPassword, user.getPassword());
            // used once: two clicks at the same time - only one marks it (the other gets 400)
            if (tokenRepository.markUsed(found.getId(), now) == 0) {
                throw invalidToken();
            }
            user.setPassword(passwordEncoder.encode(newPassword));
            user.setPasswordChangedAt(now);
            user.setSessionVersion(user.getSessionVersion() + 1);
            user.setPasswordFailCount(0);
            user.setPasswordFailWindowStart(null);
            userRepository.save(user);
            tokenRepository.invalidateOpen(user.getId(), now);
            // LGPD: the IP was only for the limit
            tokenRepository.clearRequestedIp(user.getId());
            eventPublisher.publishEvent(new PasswordChangedEvent(user.getId(), PasswordChangeChannel.RESET, now));
            return null;
        });
    }

    private PasswordResetToken usableToken(String token, LocalDateTime now) {
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        PasswordResetToken found = tokenRepository.findByTokenHashAndDeletedIsFalse(OneTimeTokens.hash(token.strip())).orElse(null);
        if (found == null || !found.isUsableAt(now)) {
            throw invalidToken();
        }
        return found;
    }

    /** Usuário ou escritório excluído depois da emissão: a mesma resposta de link inválido. */
    private void usableUser(User user) {
        if (user == null || user.isDeleted() || user.getAccountingFirm() == null || user.getAccountingFirm().isDeleted()) {
            throw invalidToken();
        }
    }

    private void assertAvailable() {
        if (!properties.isResetEnabled()) {
            throw new ServiceUnavailableException(UNAVAILABLE, messageService.getMessage(UNAVAILABLE));
        }
    }

    private BadRequestException invalidToken() {
        return new BadRequestException(TOKEN_INVALID, messageService.getMessage(TOKEN_INVALID));
    }
}
