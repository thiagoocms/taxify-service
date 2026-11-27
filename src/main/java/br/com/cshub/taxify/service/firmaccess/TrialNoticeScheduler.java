package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.firmaccess.FirmNotification;
import br.com.cshub.taxify.enumerated.FirmNotificationType;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.FirmNotificationRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Avisos do fim do teste por e-mail (F1-RN-16): todo dia ({@code TAXIFY_TRIAL_NOTICE_CRON}, horário de Recife), aos
 * e-mails dos ADMIN de cada escritório em TRIAL - TRIAL_D3 quando faltam 3 dias, TRIAL_D1 quando falta 1 e TRIAL_D0 no
 * último dia.
 * <ul>
 *   <li>Idempotente: a chave única (escritório, tipo, {@code trial_ends_on}) de firm_notifications - rodar duas vezes no
 *       mesmo dia não manda de novo. Um teste estendido tem outra data e ganha avisos novos.</li>
 *   <li>Um aviso cuja data passou (o agendador ficou parado) não sai atrasado.</li>
 *   <li>Falha de envio: nada é gravado, e a próxima rodada do mesmo dia tenta de novo.</li>
 * </ul>
 */
@Component
public class TrialNoticeScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrialNoticeScheduler.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final FirmSubscriptionRepository subscriptionRepository;
    private final FirmNotificationRepository notificationRepository;
    private final AccountingFirmRepository firmRepository;
    private final UserRepository userRepository;
    private final FirmAccessService accessService;
    private final MailSender mailSender;
    private final SaasProperties properties;
    private final Clock clock;

    public TrialNoticeScheduler(FirmSubscriptionRepository subscriptionRepository, FirmNotificationRepository notificationRepository,
                                AccountingFirmRepository firmRepository, UserRepository userRepository,
                                FirmAccessService accessService, MailSender mailSender, SaasProperties properties, Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.notificationRepository = notificationRepository;
        this.firmRepository = firmRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${taxify.saas.trial-notice-cron:0 0 9 * * *}", zone = "America/Recife")
    public void tick() {
        if (!properties.isTrialNoticeEnabled()) {
            return;
        }
        // one run at a time: a slow run is not overlapped by the next tick (convention 3.8.8)
        if (!running.compareAndSet(false, true)) {
            log.info("Trial notice run still running from the previous tick - skipped");
            return;
        }
        try {
            int sent = sendDue();
            if (sent > 0) {
                log.info("trial.notice.sent count={}", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Trial notice run failed", e);
        } finally {
            running.set(false);
        }
    }

    public int sendDue() {
        LocalDate today = LocalDate.now(clock);
        int sent = 0;
        for (FirmSubscription subscription : subscriptionRepository.findByStatusInAndDeletedIsFalse(List.of(FirmSubscriptionStatus.TRIAL))) {
            FirmAccess access = accessService.evaluate(subscription.getAccountingFirmId(), subscription);
            if (!access.trialLimited()) {
                continue;
            }
            Optional<FirmNotificationType> due = FirmAccessRules.noticeDue(subscription.getTrialEndsOn(), today);
            if (due.isEmpty() || notificationRepository.existsByAccountingFirmIdAndTypeAndReferenceDate(
                    subscription.getAccountingFirmId(), due.get(), subscription.getTrialEndsOn())) {
                continue;
            }
            if (send(subscription, due.get())) {
                sent++;
            }
        }
        return sent;
    }

    private boolean send(FirmSubscription subscription, FirmNotificationType type) {
        AccountingFirm firm = firmRepository.findFirstByIdAndDeletedIsFalse(subscription.getAccountingFirmId()).orElse(null);
        if (firm == null) {
            return false;
        }
        List<String> admins = userRepository.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId()).stream()
                .filter(user -> user.getProfile() == UserProfileEnum.ADMIN)
                .map(User::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .distinct()
                .toList();
        if (admins.isEmpty()) {
            return false;
        }
        try {
            mailSender.send(FirmMailTemplates.trialNotice(admins, firm.getName(), type, subscription.getTrialEndsOn(),
                    properties.getPublicUrl()));
        } catch (RuntimeException e) {
            // MailSendException carries only the safe detail; anything else, only its class (the text could carry an address)
            String detail = e instanceof br.com.cshub.taxify.integration.mail.MailSendException ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("trial.notice.failed firm={} type={} - tried again on the next run: {}", firm.getId(), type, detail);
            return false;
        }
        try {
            notificationRepository.save(new FirmNotification(firm.getId(), type, subscription.getTrialEndsOn(), LocalDateTime.now(clock)));
        } catch (DataIntegrityViolationException e) {
            // outra instância gravou o mesmo aviso - a chave única segurou
            return false;
        }
        return true;
    }
}
