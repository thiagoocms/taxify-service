package br.com.cshub.taxify.service.pendency.alert;

import br.com.cshub.taxify.common.DuplicateKeys;
import br.com.cshub.taxify.common.NamedLocks;
import br.com.cshub.taxify.config.PendencyAlertProperties;
import br.com.cshub.taxify.domain.pendency.PendencyAlertDigest;
import br.com.cshub.taxify.domain.pendency.PendencyAlertItem;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.enumerated.PendencyAlertDigestStatus;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.PendencyAlertDigestRepository;
import br.com.cshub.taxify.repository.PendencyAlertItemRepository;
import br.com.cshub.taxify.repository.PendencyRepository;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.service.notification.NotificationSettingsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quem recebe e o que entra no resumo diário de pendências ALTA (AV-RN-01, AV-RN-02, AV-RN-04, AV-RN-05), mais a
 * gravação do resumo (reserva, situação, itens avisados) e a limpeza (AV-RN-14).
 * <ul>
 *   <li>Elegível: usuário e escritório não excluídos, com {@code INVOICES_VIEW}, com e-mail e com o aviso ligado (ou sem
 *       registro e {@code TAXIFY_PENDENCY_ALERT_DEFAULT_ON}).</li>
 *   <li>Empresas: o ADMIN vê todas as do escritório; os outros, a carteira - sempre só do próprio escritório, sem
 *       excluídas nem inativas (a regra do painel).</li>
 *   <li>Pendência: ALTA, ABERTA, não excluída, de um tipo de {@code TAXIFY_PENDENCY_ALERT_TYPES} (vazio = todos) e
 *       ainda não avisada ao usuário no episódio atual ({@code first_seen_at}).</li>
 * </ul>
 */
@Service
public class PendencyAlertService {

    static final List<PendencyAlertDigestStatus> ATTEMPTS =
            List.of(PendencyAlertDigestStatus.SENDING, PendencyAlertDigestStatus.SENT, PendencyAlertDigestStatus.FAILED);
    static final int CLOSED_ITEMS_RETENTION_DAYS = 30;
    static final int DIGEST_RETENTION_DAYS = 365;
    static final String BUDGET_LOCK = "taxify:pendency-alert-budget";
    static final int BUDGET_LOCK_SECONDS = 30;

    /** Um destinatário do resumo: sempre um usuário, de um escritório só (AV-RN-11). */
    public record Recipient(UUID userId, UUID firmId, String firmName, String name, String email, List<UUID> companyIds) {
    }

    private final UserRepository userRepository;
    private final UserCompanyRepository userCompanyRepository;
    private final CompanyRepository companyRepository;
    private final PendencyRepository pendencyRepository;
    private final PendencyAlertDigestRepository digestRepository;
    private final PendencyAlertItemRepository itemRepository;
    private final NotificationSettingsService settingsService;
    private final PendencyAlertProperties properties;
    private final TransactionTemplate transaction;

    public PendencyAlertService(UserRepository userRepository, UserCompanyRepository userCompanyRepository,
                                CompanyRepository companyRepository, PendencyRepository pendencyRepository,
                                PendencyAlertDigestRepository digestRepository, PendencyAlertItemRepository itemRepository,
                                NotificationSettingsService settingsService, PendencyAlertProperties properties,
                                PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.userCompanyRepository = userCompanyRepository;
        this.companyRepository = companyRepository;
        this.pendencyRepository = pendencyRepository;
        this.digestRepository = digestRepository;
        this.itemRepository = itemRepository;
        this.settingsService = settingsService;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** AV-RN-02: os elegíveis destes escritórios, com as empresas que cada um vê. */
    public List<Recipient> recipients(Collection<UUID> firmIds) {
        if (firmIds.isEmpty()) {
            return List.of();
        }
        List<User> users = userRepository.findInUseByFirms(firmIds).stream()
                .filter(user -> !user.isDeleted() && user.getAccountingFirm() != null && !user.getAccountingFirm().isDeleted())
                .filter(NotificationSettingsService::eligibleForPendencyDigest)
                .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
                .toList();
        if (users.isEmpty()) {
            return List.of();
        }
        List<UUID> userIds = users.stream().map(User::getId).toList();
        Map<UUID, Boolean> enabled = settingsService.pendencyDigestEnabled(userIds);
        List<User> wanted = users.stream().filter(user -> Boolean.TRUE.equals(enabled.get(user.getId()))).toList();
        if (wanted.isEmpty()) {
            return List.of();
        }
        Map<UUID, Set<UUID>> portfolios = new HashMap<>();
        List<UUID> restricted = wanted.stream().filter(user -> user.getProfile() != UserProfileEnum.ADMIN).map(User::getId).toList();
        if (!restricted.isEmpty()) {
            for (Object[] row : userCompanyRepository.findPortfolios(restricted)) {
                portfolios.computeIfAbsent((UUID) row[0], id -> new HashSet<>()).add((UUID) row[1]);
            }
        }
        Map<UUID, List<UUID>> firmCompanies = new HashMap<>();
        List<Recipient> recipients = new ArrayList<>();
        for (User user : wanted) {
            UUID firmId = user.getAccountingFirm().getId();
            List<UUID> companies = firmCompanies.computeIfAbsent(firmId, companyRepository::findActiveIdsByFirm);
            List<UUID> visible = user.getProfile() == UserProfileEnum.ADMIN ? companies
                    // the portfolio, never a company of another firm
                    : companies.stream().filter(portfolios.getOrDefault(user.getId(), Set.of())::contains).toList();
            if (!visible.isEmpty()) {
                recipients.add(new Recipient(user.getId(), firmId, user.getAccountingFirm().getName(), user.getName(),
                        user.getEmail().strip(), visible));
            }
        }
        return recipients;
    }

    /** AV-RN-01, AV-RN-04: as pendências ALTA abertas ainda não avisadas ao usuário neste episódio. */
    public List<PendencyAlertCandidate> newPendencies(Recipient recipient) {
        return pendencyRepository.findAlertCandidates(recipient.companyIds(), types(), recipient.userId());
    }

    /** AV-RN-06: todas as ALTA abertas da carteira (novas e antigas). */
    public long openHigh(Recipient recipient) {
        return pendencyRepository.countOpenHigh(recipient.companyIds(), types());
    }

    private Set<PendencyType> types() {
        return properties.getTypes().isEmpty() ? EnumSet.allOf(PendencyType.class) : properties.getTypes();
    }

    /** O último resumo SENT de cada usuário (ausente = nunca recebeu). */
    public Map<UUID, LocalDateTime> lastSentAt(Collection<UUID> userIds) {
        Map<UUID, LocalDateTime> result = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Object[] row : digestRepository.findLastSentAt(PendencyAlertDigestStatus.SENT, userIds)) {
                if (row[1] != null) {
                    result.put((UUID) row[0], (LocalDateTime) row[1]);
                }
            }
        }
        return result;
    }

    public Set<UUID> withDigestOn(LocalDate day, Collection<UUID> userIds) {
        return userIds.isEmpty() ? Set.of() : new LinkedHashSet<>(digestRepository.findUserIdsWithDigestOn(day, userIds));
    }

    /** O que já conta no teto global do dia: as tentativas (SENDING, SENT, FAILED). */
    public long attemptsOn(LocalDate day) {
        return digestRepository.countByReferenceDateAndStatusIn(day, ATTEMPTS);
    }

    /** AV-RN-10: os SENDING de dias anteriores (queda no meio) viram FAILED. */
    public int failStaleSending(LocalDate today) {
        Integer updated = transaction.execute(status -> digestRepository.failStaleSending(today));
        return updated == null ? 0 : updated;
    }

    /**
     * Reserva o resumo do dia (AV-RN-09, AV-RN-10): SENDING se ainda cabe no teto global {@code maxPerDay}, senão
     * CAPPED. A contagem e a gravação ficam numa seção crítica entre instâncias (lock nomeado do MySQL, solto depois do
     * commit) e a contagem é uma leitura travada - duas instâncias nunca passam do teto. A chave única (usuário, dia) é a
     * reserva do usuário: devolve {@code null} quando outra rodada ou instância já gravou.
     *
     * @throws IllegalStateException quando o lock do teto não sai a tempo (nada gravado; o usuário fica para o próximo dia)
     */
    public PendencyAlertDigest reserve(Recipient recipient, LocalDate day, List<PendencyAlertCandidate> items, int maxPerDay) {
        try {
            return transaction.execute(tx -> {
                NamedLocks.Held lock = NamedLocks.untilTransactionEnds(
                        () -> digestRepository.getLock(BUDGET_LOCK, BUDGET_LOCK_SECONDS), this::releaseBudgetLock);
                if (lock == null) {
                    throw new IllegalStateException("pendency alert budget lock not obtained");
                }
                try (lock) {
                    Long attempts = digestRepository.countAttemptsLocking(day);
                    PendencyAlertDigestStatus status = (attempts == null ? 0 : attempts) < maxPerDay
                            ? PendencyAlertDigestStatus.SENDING : PendencyAlertDigestStatus.CAPPED;
                    return digestRepository.saveAndFlush(new PendencyAlertDigest(recipient.firmId(), recipient.userId(), day,
                            status, PendencyAlertMailTemplate.companiesCount(items), items.size()));
                }
            });
        } catch (DataIntegrityViolationException e) {
            if (DuplicateKeys.isDuplicateKey(e)) {
                return null;
            }
            throw e;
        }
    }

    private void releaseBudgetLock() {
        try {
            digestRepository.releaseLock(BUDGET_LOCK);
        } catch (RuntimeException e) {
            // logged only: MySQL frees it anyway when the connection closes
            org.slf4j.LoggerFactory.getLogger(PendencyAlertService.class)
                    .warn("pendency.alert.lock.release.failed error={}", e.getClass().getSimpleName());
        }
    }

    /** Enviado: SENT, a hora e os itens avisados neste episódio (AV-RN-04). */
    public void markSent(PendencyAlertDigest digest, List<PendencyAlertCandidate> items, LocalDateTime sentAt) {
        transaction.executeWithoutResult(status -> {
            digestRepository.updateStatus(digest.getId(), PendencyAlertDigestStatus.SENT, sentAt);
            List<PendencyAlertItem> rows = items.stream()
                    .map(item -> new PendencyAlertItem(digest.getId(), item.pendencyId(), digest.getUserId(), item.firstSeenAt()))
                    .toList();
            itemRepository.saveAll(rows);
        });
    }

    /** Falhou: FAILED e nada avisado - as pendências vão no próximo dia útil. */
    public void markFailed(PendencyAlertDigest digest) {
        transaction.executeWithoutResult(status -> digestRepository.updateStatus(digest.getId(), PendencyAlertDigestStatus.FAILED, null));
    }

    /** AV-RN-14: itens de pendência fechada há mais de 30 dias e resumos com mais de 365 dias. */
    public int[] cleanup(LocalDate today, LocalDateTime now) {
        int[] removed = new int[2];
        transaction.executeWithoutResult(status -> {
            removed[0] = itemRepository.deleteOfPendenciesClosedBefore(now.minusDays(CLOSED_ITEMS_RETENTION_DAYS));
            LocalDate digestsBefore = today.minusDays(DIGEST_RETENTION_DAYS);
            itemRepository.detachFromDigestsBefore(digestsBefore);
            removed[1] = digestRepository.deleteBefore(digestsBefore);
        });
        return removed;
    }
}
