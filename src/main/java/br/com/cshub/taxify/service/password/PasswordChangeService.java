package br.com.cshub.taxify.service.password;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.domain.auth.Auth;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.password.PasswordChangeResponseDTO;
import br.com.cshub.taxify.enumerated.PasswordChangeChannel;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.TooManyRequestsException;
import br.com.cshub.taxify.exception.UnauthorizedException;
import br.com.cshub.taxify.repository.PasswordResetTokenRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.TokenService;
import br.com.cshub.taxify.validation.PasswordPolicy;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Troca da própria senha pelo usuário logado (SN-RN-09), {@code PUT /users/me/password}.
 * <ul>
 *   <li>Senha atual errada: 400 {@code error.user.password.current.invalid} (400, e não 401, para o front não deslogar).</li>
 *   <li>Limite de erro: depois de {@code TAXIFY_PASSWORD_CHANGE_MAX_FAILURES} erros numa janela de 60 minutos (contada
 *       do primeiro erro), 429 {@code error.user.password.change.rate.limited} até a janela acabar, <b>mesmo com a senha
 *       certa</b>. O contador fica no usuário (sobrevive a restart) e zera numa troca certa. O erro é gravado numa
 *       transação própria, que é confirmada antes do 400.</li>
 *   <li>Sucesso: senha nova (regra única, diferente da atual), {@code password_changed_at}, versão de sessão + 1 (as
 *       outras sessões caem), links de redefinição abertos invalidados, aviso depois do commit e um JWT novo para a
 *       sessão atual continuar.</li>
 * </ul>
 */
@Service
public class PasswordChangeService {

    static final String CURRENT_INVALID = "error.user.password.current.invalid";
    static final String RATE_LIMITED = "error.user.password.change.rate.limited";
    private static final int WINDOW_MINUTES = 60;

    private final CurrentUserService currentUserService;
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final PasswordProperties properties;
    private final MessageService messageService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final EntityManager entityManager;

    public PasswordChangeService(CurrentUserService currentUserService, UserRepository userRepository,
                                 PasswordResetTokenRepository tokenRepository, PasswordPolicy passwordPolicy,
                                 PasswordEncoder passwordEncoder, TokenService tokenService, PasswordProperties properties,
                                 MessageService messageService, ApplicationEventPublisher eventPublisher, Clock clock,
                                 PlatformTransactionManager transactionManager, EntityManager entityManager) {
        this.entityManager = entityManager;
        this.currentUserService = currentUserService;
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.properties = properties;
        this.messageService = messageService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    private enum Outcome { CHANGED, WRONG_CURRENT, RATE_LIMITED }

    private record Result(Outcome outcome, String token) {
    }

    public PasswordChangeResponseDTO changeOwn(String currentPassword, String newPassword) {
        UUID userId = currentUserService.getCurrentUser().map(User::getId)
                .orElseThrow(() -> new UnauthorizedException("error.auth.session.expired",
                        messageService.getMessage("error.auth.session.expired")));
        Result result = transaction.execute(status -> change(userId, currentPassword, newPassword));
        return switch (result.outcome()) {
            case CHANGED -> new PasswordChangeResponseDTO(result.token());
            case WRONG_CURRENT -> throw new BadRequestException(CURRENT_INVALID, messageService.getMessage(CURRENT_INVALID));
            case RATE_LIMITED -> throw new TooManyRequestsException(RATE_LIMITED, messageService.getMessage(RATE_LIMITED));
        };
    }

    /** Com a linha do usuário travada: o contador de erros não perde incremento entre duas abas. */
    private Result change(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findForUpdate(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new UnauthorizedException("error.auth.session.expired",
                        messageService.getMessage("error.auth.session.expired")));
        // the user was read earlier in this request: after the lock, what another tab committed (the failure counter)
        if (entityManager.contains(user)) {
            entityManager.refresh(user);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        boolean windowOpen = user.getPasswordFailWindowStart() != null
                && now.isBefore(user.getPasswordFailWindowStart().plusMinutes(WINDOW_MINUTES));
        if (!windowOpen) {
            user.setPasswordFailCount(0);
            user.setPasswordFailWindowStart(null);
        }
        if (windowOpen && user.getPasswordFailCount() >= properties.getChangeMaxFailures()) {
            return new Result(Outcome.RATE_LIMITED, null);
        }
        if (currentPassword == null || currentPassword.isEmpty() || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            if (user.getPasswordFailWindowStart() == null) {
                user.setPasswordFailWindowStart(now);
            }
            user.setPasswordFailCount(user.getPasswordFailCount() + 1);
            userRepository.save(user);
            // committed: the 400 is thrown only after the transaction (changeOwn)
            return new Result(Outcome.WRONG_CURRENT, null);
        }
        // a password refused by the rule rolls back - the failure counter is not touched by it
        passwordPolicy.assertAcceptableChange(newPassword, user.getPassword());
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(now);
        user.setSessionVersion(user.getSessionVersion() + 1);
        user.setPasswordFailCount(0);
        user.setPasswordFailWindowStart(null);
        userRepository.save(user);
        tokenRepository.invalidateOpen(user.getId(), now);
        eventPublisher.publishEvent(new PasswordChangedEvent(user.getId(), PasswordChangeChannel.SELF, now));
        // the token of the current session, already with the new session version
        return new Result(Outcome.CHANGED, tokenService.generateToken(new Auth(user)));
    }
}
