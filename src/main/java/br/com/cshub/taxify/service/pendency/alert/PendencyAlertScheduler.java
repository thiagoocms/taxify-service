package br.com.cshub.taxify.service.pendency.alert;

import br.com.cshub.taxify.config.PendencyAlertProperties;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.pendency.PendencyAlertDigest;
import br.com.cshub.taxify.enumerated.PendencyAlertDigestStatus;
import br.com.cshub.taxify.integration.mail.MailSendException;
import br.com.cshub.taxify.integration.mail.MailSender;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.RoutineGate;
import br.com.cshub.taxify.service.pendency.alert.PendencyAlertService.Recipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resumo diário das pendências ALTA por e-mail (spec 2026-11-27, AV-RN-03, AV-RN-07, AV-RN-09, AV-RN-10, AV-RN-14).
 * {@code TAXIFY_PENDENCY_ALERT_CRON} (padrão 07:50 de segunda a sexta, horário de Recife) e
 * {@code TAXIFY_PENDENCY_ALERTS_ENABLED} (padrão desligado).
 * <ol>
 *   <li>Os SENDING de dias anteriores viram FAILED (a aplicação caiu no meio).</li>
 *   <li>Os escritórios passam pelo RoutineGate: bloqueado, suspenso, cancelado ou não verificado não recebe.</li>
 *   <li>Ordem do teto: quem está há mais tempo sem receber vem primeiro (nunca recebeu = primeiro).</li>
 *   <li>Por usuário, no máximo um resumo por dia; sem pendência nova, nada (nem linha). Acima do teto global
 *       ({@code TAXIFY_PENDENCY_ALERT_MAX_PER_DAY}, contando as tentativas do dia): CAPPED, nada avisado.</li>
 *   <li>Reserva SENDING (chave única: duas instâncias nunca mandam o mesmo), envio para um destinatário só, SENT com os
 *       itens avisados, ou FAILED sem nada avisado. O ritmo entre envios é o do MailSender
 *       ({@code TAXIFY_MAIL_SEND_INTERVAL_MS}).</li>
 *   <li>Limpeza depois do envio.</li>
 * </ol>
 * Falha num usuário não para os outros. Logs só com ids e contagens - nunca e-mail, nome de empresa ou CNPJ.
 */
@Component
public class PendencyAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendencyAlertScheduler.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final PendencyAlertService service;
    private final AccountingFirmRepository firmRepository;
    private final FirmAccessService firmAccessService;
    private final MailSender mailSender;
    private final PendencyAlertProperties properties;
    private final SaasProperties saasProperties;
    private final Clock clock;

    public PendencyAlertScheduler(PendencyAlertService service, AccountingFirmRepository firmRepository,
                                  FirmAccessService firmAccessService, MailSender mailSender,
                                  PendencyAlertProperties properties, SaasProperties saasProperties, Clock clock) {
        this.service = service;
        this.firmRepository = firmRepository;
        this.firmAccessService = firmAccessService;
        this.mailSender = mailSender;
        this.properties = properties;
        this.saasProperties = saasProperties;
        this.clock = clock;
    }

    /** O que uma rodada fez - para o log e os testes. */
    public record RunResult(int sent, int failed, int capped, int skippedRecipients) {
    }

    @Scheduled(cron = "${taxify.pendency-alerts.cron:0 50 7 * * MON-FRI}", zone = "America/Recife")
    public void tick() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Pendency alert run still running from the previous tick - skipped");
            return;
        }
        try {
            RunResult result = sendDue();
            log.info("pendency.alert.run sent={} failed={} capped={}", result.sent(), result.failed(), result.capped());
        } catch (RuntimeException e) {
            log.warn("Pendency alert run failed: {}", e.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    public RunResult sendDue() {
        LocalDate today = LocalDate.now(clock);
        service.failStaleSending(today);

        RoutineGate gate = firmAccessService.routineGate("pendency-alerts");
        List<UUID> firms = firmRepository.findIdsInUse().stream().filter(gate::allowsFirm).toList();
        gate.logSkipped();

        List<Recipient> recipients = service.recipients(firms);
        List<UUID> userIds = recipients.stream().map(Recipient::userId).toList();
        Set<UUID> alreadyToday = service.withDigestOn(today, userIds);
        Map<UUID, LocalDateTime> lastSent = service.lastSentAt(userIds);
        List<Recipient> ordered = recipients.stream()
                .filter(recipient -> !alreadyToday.contains(recipient.userId()))
                .sorted(Comparator.comparing((Recipient recipient) -> lastSent.get(recipient.userId()),
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(recipient -> recipient.userId().toString()))
                .toList();

        int sent = 0;
        int failed = 0;
        int capped = 0;
        int skipped = 0;
        for (Recipient recipient : ordered) {
            try {
                if (!mailSender.accepts(recipient.email())) {
                    // TAXIFY_MAIL_RECIPIENT_ALLOWLIST: nothing reserved, nothing marked
                    skipped++;
                    continue;
                }
                List<PendencyAlertCandidate> items = service.newPendencies(recipient);
                if (items.isEmpty()) {
                    continue;
                }
                // the cap is counted again at each reservation, atomically across instances (PendencyAlertService#reserve)
                PendencyAlertDigest digest = service.reserve(recipient, today, items, properties.getMaxPerDay());
                if (digest == null) {
                    // another run or instance has it
                    continue;
                }
                if (digest.getStatus() == PendencyAlertDigestStatus.CAPPED) {
                    capped++;
                    continue;
                }
                if (send(recipient, digest, items)) {
                    sent++;
                } else {
                    failed++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("pendency.alert.failed user={} error={}", recipient.userId(), e.getClass().getSimpleName());
            }
        }
        if (capped > 0) {
            log.info("pendency.alert.capped count={}", capped);
        }
        if (skipped > 0) {
            log.info("pendency.alert.recipient.skipped count={}", skipped);
        }
        try {
            int[] removed = service.cleanup(today, LocalDateTime.now(clock));
            if (removed[0] > 0 || removed[1] > 0) {
                log.info("pendency.alert.cleanup items={} digests={}", removed[0], removed[1]);
            }
        } catch (RuntimeException e) {
            log.warn("pendency.alert.cleanup.failed error={}", e.getClass().getSimpleName());
        }
        return new RunResult(sent, failed, capped, skipped);
    }

    private boolean send(Recipient recipient, PendencyAlertDigest digest, List<PendencyAlertCandidate> items) {
        try {
            long totalOpen = service.openHigh(recipient);
            mailSender.send(PendencyAlertMailTemplate.build(new PendencyAlertMailTemplate.Digest(recipient.email(),
                    recipient.name(), recipient.firmName(), items, Math.max(totalOpen, items.size()),
                    saasProperties.getPublicUrl(), properties.getMaxCompaniesListed())));
        } catch (RuntimeException e) {
            String detail = e instanceof MailSendException ? e.getMessage() : "error=" + e.getClass().getSimpleName();
            log.warn("pendency.alert.failed user={} digest={} {}", recipient.userId(), digest.getId(), detail);
            try {
                service.markFailed(digest);
            } catch (RuntimeException markError) {
                // stays SENDING: the next day's run counts it as FAILED
                log.warn("pendency.alert.failed user={} digest={} - status not written", recipient.userId(), digest.getId());
            }
            return false;
        }
        service.markSent(digest, items, LocalDateTime.now(clock));
        log.info("pendency.alert.sent user={} firm={} digest={} companies={} pendencies={}", recipient.userId(),
                recipient.firmId(), digest.getId(), digest.getCompaniesCount(), digest.getPendenciesCount());
        return true;
    }
}
