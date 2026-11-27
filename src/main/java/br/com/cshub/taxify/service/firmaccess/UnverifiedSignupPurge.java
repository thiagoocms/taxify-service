package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmEventType;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.DocumentRepository;
import br.com.cshub.taxify.repository.EmailVerificationRepository;
import br.com.cshub.taxify.repository.FirmNotificationRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionEventRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.TermsAcceptanceRepository;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Expurgo do cadastro não verificado (F1-RN-03): uma vez por dia, o escritório ainda em PENDING_VERIFICATION
 * cadastrado há mais de {@code TAXIFY_UNVERIFIED_PURGE_DAYS} dias (7 × 24 h desde o POST /signup - reenvios não
 * reiniciam a contagem) é apagado de vez: escritório, usuário, documento (CPF), aceite, links e assinatura. O CNPJ, o
 * login e o CPF ficam livres de novo. Fica só o evento UNVERIFIED_PURGED, sem dado pessoal.
 *
 * <p>Exceção de propósito à regra do soft delete: aqui a exclusão é física (LGPD) - é o único lugar que apaga linhas.
 */
@Component
public class UnverifiedSignupPurge {

    private static final Logger log = LoggerFactory.getLogger(UnverifiedSignupPurge.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final FirmSubscriptionRepository subscriptionRepository;
    private final TermsAcceptanceRepository termsRepository;
    private final EmailVerificationRepository verificationRepository;
    private final FirmNotificationRepository notificationRepository;
    private final FirmSubscriptionEventRepository eventRepository;
    private final UserRepository userRepository;
    private final UserCompanyRepository userCompanyRepository;
    private final DocumentRepository documentRepository;
    private final AccountingFirmRepository firmRepository;
    private final FirmEventService eventService;
    private final SaasProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final UserOwnedRowsPurge userOwnedRows;

    public UnverifiedSignupPurge(FirmSubscriptionRepository subscriptionRepository, TermsAcceptanceRepository termsRepository,
                                 EmailVerificationRepository verificationRepository,
                                 FirmNotificationRepository notificationRepository,
                                 FirmSubscriptionEventRepository eventRepository, UserRepository userRepository,
                                 UserCompanyRepository userCompanyRepository, DocumentRepository documentRepository,
                                 AccountingFirmRepository firmRepository, FirmEventService eventService,
                                 SaasProperties properties, Clock clock, PlatformTransactionManager transactionManager,
                                 UserOwnedRowsPurge userOwnedRows) {
        this.userOwnedRows = userOwnedRows;
        this.subscriptionRepository = subscriptionRepository;
        this.termsRepository = termsRepository;
        this.verificationRepository = verificationRepository;
        this.notificationRepository = notificationRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.userCompanyRepository = userCompanyRepository;
        this.documentRepository = documentRepository;
        this.firmRepository = firmRepository;
        this.eventService = eventService;
        this.properties = properties;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "${taxify.saas.purge-cron:0 20 3 * * *}", zone = "America/Recife")
    public void tick() {
        if (!properties.isPurgeEnabled()) {
            return;
        }
        // one run at a time: a slow run is not overlapped by the next tick (convention 3.8.8)
        if (!running.compareAndSet(false, true)) {
            log.info("Unverified signup purge still running from the previous tick - skipped");
            return;
        }
        try {
            int purged = purgeExpired();
            if (purged > 0) {
                log.info("signup.unverified.purged count={}", purged);
            }
        } catch (RuntimeException e) {
            log.warn("Unverified signup purge failed", e);
        } finally {
            running.set(false);
        }
    }

    public int purgeExpired() {
        LocalDateTime now = LocalDateTime.now(clock);
        int purged = 0;
        for (FirmSubscription pending : subscriptionRepository.findByStatusInAndDeletedIsFalse(
                List.of(FirmSubscriptionStatus.PENDING_VERIFICATION))) {
            UUID firmId = pending.getAccountingFirmId();
            LocalDateTime signupAt = termsRepository.findSignupAt(firmId).orElse(null);
            if (!isExpired(signupAt, now, properties.getUnverifiedPurgeDays())) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(transaction.execute(status -> purge(firmId)))) {
                    purged++;
                }
            } catch (RuntimeException e) {
                log.warn("Unverified signup of firm {} not purged: {}", firmId, e.getMessage());
            }
        }
        return purged;
    }

    /** Mais de {@code days} × 24 h desde o cadastro; sem o aceite (não deveria acontecer) fica para a CS Hub ver. */
    static boolean isExpired(LocalDateTime signupAt, LocalDateTime now, int days) {
        return signupAt != null && now.isAfter(signupAt.plusDays(days));
    }

    /** @return false when the firm is no longer pending (verified meanwhile) - then nothing is touched */
    private boolean purge(UUID firmId) {
        // the same row the verification locks (EmailVerificationService#verify): whoever comes second sees the other's result
        List<String> current = subscriptionRepository.findStatusForUpdate(firmId.toString());
        if (current.isEmpty() || !FirmSubscriptionStatus.PENDING_VERIFICATION.name().equals(current.getFirst())) {
            return false;
        }
        // children first, in foreign key order: links, profiles and portfolio of the users, then the firm's rows
        List<Object[]> users = userRepository.findIdsAndDocumentIdsByFirm(firmId);
        List<UUID> userIds = users.stream().map(row -> (UUID) row[0]).toList();
        List<UUID> documentIds = users.stream().map(row -> (UUID) row[1]).filter(Objects::nonNull).toList();
        if (!userIds.isEmpty()) {
            verificationRepository.deleteByUserIds(userIds);
            // the rows of the password and notice features that point to the users (spec 2026-11-27)
            userOwnedRows.deleteOf(userIds);
            userRepository.deleteAccessProfilesOf(userIds.stream().map(UUID::toString).toList());
            userCompanyRepository.deleteByUserIds(userIds);
        }
        termsRepository.deleteByFirm(firmId);
        notificationRepository.deleteByFirm(firmId);
        subscriptionRepository.deleteByFirm(firmId);
        userRepository.deleteByFirm(firmId);
        if (!documentIds.isEmpty()) {
            documentRepository.deleteByIds(documentIds);
        }
        firmRepository.deleteByIdPhysically(firmId);
        // defence (F1-RN-03): the only events that carry personal data are the contact ones - they go with the signup
        eventRepository.deleteByFirmAndType(firmId, FirmEventType.CONTACT_UPDATED);
        eventService.record(firmId, FirmEventType.UNVERIFIED_PURGED, null, null, null, FirmEventService.SYSTEM);
        return true;
    }
}
