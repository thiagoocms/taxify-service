package br.com.cshub.taxify.service.password;

import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.PasswordChangeChannel;
import br.com.cshub.taxify.integration.mail.MailSendException;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import br.com.cshub.taxify.config.PasswordAsyncConfig;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * Depois do commit de uma troca de senha (SN-RN-12, SN-RN-13): o log só com o id ({@code password.reset.completed} ou
 * {@code password.changed by=self|admin}) e o e-mail de "senha alterada" ao endereço da conta, sem a senha e sem link
 * de ação, enviado fora da thread da requisição (executor limitado, PasswordAsyncConfig). Falha no envio só gera log ({@code password.notice.failed}); a troca não é desfeita.
 */
@Component
public class PasswordChangeNotifier {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeNotifier.class);

    private final UserRepository userRepository;
    private final MailSender mailSender;
    private final PasswordProperties properties;
    private final Executor executor;

    public PasswordChangeNotifier(UserRepository userRepository, MailSender mailSender, PasswordProperties properties,
                                  @Qualifier(PasswordAsyncConfig.EXECUTOR) Executor executor) {
        this.executor = executor;
        this.userRepository = userRepository;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPasswordChanged(PasswordChangedEvent event) {
        if (event.channel() == PasswordChangeChannel.RESET) {
            log.info("password.reset.completed user={}", event.userId());
        } else {
            log.info("password.changed user={} by={}", event.userId(), event.channel().name().toLowerCase(Locale.ROOT));
        }
        if (!properties.isChangedNoticeEnabled()) {
            return;
        }
        // off the request thread: the new JWT (PUT /users/me/password) and the ADMIN's save never wait for the SES
        try {
            executor.execute(() -> send(event));
        } catch (RejectedExecutionException e) {
            log.warn("password.notice.dropped user={}", event.userId());
        }
    }

    void send(PasswordChangedEvent event) {
        try {
            User user = userRepository.findById(event.userId()).orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                return;
            }
            mailSender.send(PasswordMailTemplates.changed(user.getEmail().strip(), user.getLogin(), event.changedAt(), event.channel()));
        } catch (MailSendException e) {
            log.warn("password.notice.failed user={} {}", event.userId(), e.getMessage());
        } catch (RuntimeException e) {
            // only the class: the text could carry the address
            log.warn("password.notice.failed user={} error={}", event.userId(), e.getClass().getSimpleName());
        }
    }
}
