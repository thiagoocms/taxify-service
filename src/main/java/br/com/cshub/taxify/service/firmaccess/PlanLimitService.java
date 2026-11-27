package br.com.cshub.taxify.service.firmaccess;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.SaasProperties;
import br.com.cshub.taxify.dto.firmaccess.FirmAccessLimitsDTO;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FirmSubscriptionRepository;
import br.com.cshub.taxify.repository.UserRepository;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Limite do teste grátis (F1-RN-10a): em TRIAL, no máximo {@code TAXIFY_TRIAL_MAX_COMPANIES} empresas em uso e
 * {@code TAXIFY_TRIAL_MAX_USERS} usuários (o ADMIN conta). O escritório ACTIVE e o isento não têm limite nesta fase.
 *
 * <ul>
 *   <li>Empresa em uso: não excluída e {@code active} nulo ou true - a inativa não ocupa vaga. Cada CNPJ completo é uma
 *       empresa: matriz e filiais contam à parte, sem agrupar pela raiz.</li>
 *   <li>Usuário em uso: não excluído, de qualquer tipo.</li>
 *   <li>Concorrência: a conferência roda na transação que grava, depois de travar a linha do escritório
 *       ({@code SELECT ... FOR UPDATE}); as contagens são leituras com trava, que enxergam o que outra transação
 *       acabou de gravar. Estouro da espera da trava: 409 {@code error.plan.limit.busy}.</li>
 * </ul>
 */
@Service
public class PlanLimitService {

    public static final String TRIAL_PLAN_NAME = "Teste grátis";
    /** Espera máxima pela trava do escritório - estourou, 409 error.plan.limit.busy. */
    static final int LOCK_WAIT_SECONDS = 5;
    private static final Logger log = LoggerFactory.getLogger(PlanLimitService.class);

    public enum Kind { COMPANY, USER }

    private final AccountingFirmRepository firmRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final FirmSubscriptionRepository subscriptionRepository;
    private final FirmAccessService firmAccessService;
    private final SaasProperties properties;
    private final MessageService messageService;
    private final TransactionTemplate transaction;

    public PlanLimitService(AccountingFirmRepository firmRepository, CompanyRepository companyRepository,
                            UserRepository userRepository, FirmSubscriptionRepository subscriptionRepository,
                            FirmAccessService firmAccessService, SaasProperties properties,
                            MessageService messageService, PlatformTransactionManager transactionManager) {
        this.firmRepository = firmRepository;
        this.companyRepository = companyRepository;
        this.userRepository = userRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.firmAccessService = firmAccessService;
        this.properties = properties;
        this.messageService = messageService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** O escritório está sujeito ao limite do teste agora. */
    public boolean isLimited(UUID firmId) {
        return firmId != null && firmAccessService.accessOf(firmId).trialLimited();
    }

    /**
     * Antes de criar ou reativar uma empresa (POST /companies, PUT que reativa): 409 {@code error.plan.limit.companies}
     * sem vaga. Precisa rodar dentro da transação que grava a empresa - a trava vale até o commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertCompanySlot(UUID firmId) {
        assertSlot(firmId, Kind.COMPANY);
    }

    /** Antes de criar um usuário (POST /users): 409 {@code error.plan.limit.users} sem vaga. Dentro da transação que grava. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertUserSlot(UUID firmId) {
        assertSlot(firmId, Kind.USER);
    }

    /**
     * Para a importação da Acessórias: cria (ou reativa) uma empresa só se houver vaga, numa transação própria com a
     * trava do escritório - por empresa, nunca pelo job inteiro. Vazio = sem vaga (a empresa entra em
     * {@code skippedByLimit}). Sem limite, só executa.
     */
    public <T> Optional<T> withCompanySlot(UUID firmId, Supplier<T> write) {
        return transaction.execute(status -> {
            if (!hasSlot(firmId, Kind.COMPANY, "acessorias-import")) {
                return Optional.<T>empty();
            }
            return Optional.ofNullable(write.get());
        });
    }

    private void assertSlot(UUID firmId, Kind kind) {
        String path = currentPath();
        if (!hasSlot(firmId, kind, path)) {
            int limit = limitOf(kind);
            long inUse = countInUse(firmId, kind);
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("kind", kind.name());
            extras.put("limit", limit);
            extras.put("inUse", inUse);
            extras.put("planName", TRIAL_PLAN_NAME);
            String code = kind == Kind.COMPANY ? "error.plan.limit.companies" : "error.plan.limit.users";
            throw new ConflictException(code, messageService.getMessage(code, String.valueOf(limit), String.valueOf(inUse)), extras);
        }
    }

    /** Trava o escritório e confere a vaga; com o limite atingido registra {@code plan.limit.blocked} (sem dado pessoal). */
    private boolean hasSlot(UUID firmId, Kind kind, String path) {
        if (firmId == null) {
            return true;
        }
        // without the trial now, no lock at all (ACTIVE and the exempt firm have no limit in this phase)
        if (!firmAccessService.accessOf(firmId).trialLimited()) {
            return true;
        }
        // wait at most LOCK_WAIT_SECONDS for the firm's lock (MySQL's default is 50 s), then the session's value is restored
        Integer previousWait = firmRepository.findLockWaitTimeout();
        firmRepository.setLockWaitTimeout(LOCK_WAIT_SECONDS);
        try {
            firmRepository.lock(firmId);
            // decided again under the lock: a release or conversion committed meanwhile by the platform admin wins
            if (!lockedAccess(firmId).trialLimited()) {
                return true;
            }
            int limit = limitOf(kind);
            long inUse = countInUse(firmId, kind);
            if (inUse < limit) {
                return true;
            }
            log.info("plan.limit.blocked firm={} kind={} limit={} inUse={} path={}", firmId, kind, limit, inUse, path);
            return false;
        } catch (PessimisticLockingFailureException | QueryTimeoutException e) {
            log.warn("plan.limit.busy firm={} kind={} path={}", firmId, kind, path);
            throw new ConflictException("error.plan.limit.busy", messageService.getMessage("error.plan.limit.busy"));
        } finally {
            firmRepository.setLockWaitTimeout(previousWait == null || previousWait < 1 ? 50 : previousWait);
        }
    }

    /** The firm's access read with the lock (FirmSubscriptionRepository#findAccessForUpdate); no row = SUSPENDED. */
    private FirmAccess lockedAccess(UUID firmId) {
        List<Object[]> rows = subscriptionRepository.findAccessForUpdate(firmId.toString());
        FirmSubscription subscription = null;
        if (!rows.isEmpty()) {
            Object[] row = rows.getFirst();
            subscription = new FirmSubscription();
            subscription.setAccountingFirmId(firmId);
            subscription.setStatus(FirmSubscriptionStatus.valueOf((String) row[0]));
            subscription.setTrialEndsOn(toDate(row[1]));
            subscription.setAccessUntil(toDate(row[2]));
            subscription.setBlockedSince(toDate(row[3]));
            subscription.setBlockedReason(row[4] == null ? null : BlockedReason.valueOf((String) row[4]));
        }
        return firmAccessService.evaluate(firmId, subscription);
    }

    private static LocalDate toDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return (LocalDate) value;
    }

    /** Uso × limite do teste para a tela (sem trava); nulo quando o escritório não está sujeito ao limite. */
    public FirmAccessLimitsDTO limitsFor(FirmAccess access) {
        if (access == null || !access.trialLimited()) {
            return null;
        }
        long companies = companyRepository.countInUseByFirm(access.firmId());
        long users = userRepository.countByAccountingFirm_IdAndDeletedIsFalse(access.firmId());
        return new FirmAccessLimitsDTO(TRIAL_PLAN_NAME, properties.getTrialMaxCompanies(), companies,
                properties.getTrialMaxUsers(), users);
    }

    private int limitOf(Kind kind) {
        return kind == Kind.COMPANY ? properties.getTrialMaxCompanies() : properties.getTrialMaxUsers();
    }

    /** Contagem com trava compartilhada: enxerga o que acabou de ser gravado por outra transação (F1-RN-10a). */
    long countInUse(UUID firmId, Kind kind) {
        Long count = kind == Kind.COMPANY
                ? companyRepository.countInUseLocking(firmId.toString())
                : userRepository.countInUseLocking(firmId.toString());
        return count == null ? 0 : count;
    }

    private static String currentPath() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            return request.getMethod() + " " + (pattern == null ? "?" : pattern);
        }
        return "system";
    }
}
