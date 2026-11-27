package br.com.cshub.taxify.service.password;

import br.com.cshub.taxify.config.PasswordProperties;
import br.com.cshub.taxify.repository.PasswordResetTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Limpeza diária dos links de redefinição de senha (SN-RN-15): apaga fisicamente os pedidos com mais de 48 h. Os
 * limites só olham as últimas 24 h, e o IP de quem pediu não fica guardado além disso (LGPD).
 * {@code TAXIFY_PASSWORD_RESET_PURGE_CRON} (padrão 03:30, horário de Recife) e {@code TAXIFY_PASSWORD_RESET_PURGE_ENABLED}.
 */
@Component
public class PasswordResetPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetPurgeScheduler.class);
    static final int RETENTION_HOURS = 48;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final PasswordResetTokenRepository repository;
    private final PasswordProperties properties;
    private final Clock clock;

    public PasswordResetPurgeScheduler(PasswordResetTokenRepository repository, PasswordProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${taxify.password.reset-purge-cron:0 30 3 * * *}", zone = "America/Recife")
    public void tick() {
        if (!properties.isResetPurgeEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Password reset purge still running from the previous tick - skipped");
            return;
        }
        try {
            int removed = purge();
            if (removed > 0) {
                log.info("password.reset.purged count={}", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Password reset purge failed - tried again on the next run: {}", e.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    public int purge() {
        return repository.deleteRequestedBefore(LocalDateTime.now(clock).minusHours(RETENTION_HOURS));
    }
}
