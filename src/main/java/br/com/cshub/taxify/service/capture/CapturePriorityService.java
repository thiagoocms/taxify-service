package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.DuplicateKeys;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.CapturePriorityProperties;
import br.com.cshub.taxify.config.ClockConfig;
import br.com.cshub.taxify.domain.capture.CapturePriorityRequest;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.capture.CapturePriorityResponseDTO;
import br.com.cshub.taxify.enumerated.CapturePriorityStatus;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.exception.ForbiddenException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.exception.ServiceUnavailableException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.repository.CapturePriorityRequestRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Sincronizar agora" (CP-RN-15 a CP-RN-20): o pedido põe a empresa no início da fila SIEG - a captura <b>não</b> roda
 * na requisição (o SIEG aceita 2 chamadas por minuto para a aplicação inteira). As verificações seguem a ordem da
 * CP-RN-17; a concorrência é resolvida pela chave única {@code open_key}. O lado do agendador
 * ({@link #prepareTick}, {@link #prioritySlots}, {@link #reserved}, {@link #served}) fica aqui também, para as regras
 * de expirar, cancelar e atender estarem num lugar só. Logs só com ids (CP-RN-19).
 */
@Service
public class CapturePriorityService {

    private static final Logger log = LoggerFactory.getLogger(CapturePriorityService.class);

    private final CapturePriorityRequestRepository repository;
    private final CompanyRepository companyRepository;
    private final CapturePriorityProperties properties;
    private final FiscalXmlSyncProperties syncProperties;
    private final FirmAccessService firmAccessService;
    private final CaptureEntitlement entitlement;
    private final CapturePriorityWriter writer;
    private final CurrentUserService currentUserService;
    private final MessageService messageService;
    private final Clock clock;
    /** Lote de 1: a rodada anterior foi de pedido (a próxima é da fila normal). Em memória, por instância. */
    private final AtomicBoolean lastTickWasPriority = new AtomicBoolean(false);
    private final AtomicBoolean purging = new AtomicBoolean(false);

    public CapturePriorityService(CapturePriorityRequestRepository repository, CompanyRepository companyRepository,
                                  CapturePriorityProperties properties, FiscalXmlSyncProperties syncProperties,
                                  FirmAccessService firmAccessService, CaptureEntitlement entitlement,
                                  CurrentUserService currentUserService, MessageService messageService, Clock clock,
                                  CapturePriorityWriter writer) {
        this.writer = writer;
        this.repository = repository;
        this.companyRepository = companyRepository;
        this.properties = properties;
        this.syncProperties = syncProperties;
        this.firmAccessService = firmAccessService;
        this.entitlement = entitlement;
        this.currentUserService = currentUserService;
        this.messageService = messageService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ the request (POST sync-now)

    /**
     * @param platform true = rota da plataforma ({@code @PlatformAdminOnly}, qualquer escritório); false = rota do
     *                 escritório ({@code @AdminOnly}, só empresa do próprio escritório - outra responde 404)
     */
    public CapturePriorityResponseDTO request(UUID companyId, boolean platform) {
        User user = currentUserService.getCurrentUser()
                .orElseThrow(() -> new ForbiddenException("error.access.denied", messageService.getMessage("error.access.denied")));
        if (!platform && user.getAccountingFirm() == null) {
            throw new ForbiddenException("error.access.denied", messageService.getMessage("error.access.denied"));
        }
        Company company = companyRepository.findById(companyId).filter(found -> !found.isDeleted())
                .orElseThrow(this::companyNotFound);
        UUID firmId = company.getAccountingFirm().getId();
        if (!platform && !firmId.equals(user.getAccountingFirm().getId())) {
            throw companyNotFound();
        }

        // 1-2: escritório bloqueado (a rota do escritório já para no filtro de somente leitura - F1-RN-08)
        if (!firmAccessService.accessOf(firmId).allowsAutomaticRoutines()) {
            if (platform) {
                throw new ConflictException("error.capture.priority.firm.blocked",
                        messageService.getMessage("error.capture.priority.firm.blocked"));
            }
            throw new ForbiddenException("error.firm.access.blocked", messageService.getMessage("error.firm.access.blocked"));
        }
        // 3: captura SIEG desligada ou o botão desligado
        if (!syncProperties.isEnabled() || !properties.isEnabled()) {
            throw new ServiceUnavailableException("error.capture.priority.unavailable",
                    messageService.getMessage("error.capture.priority.unavailable"));
        }
        // 4: sem direito ao canal SIEG (a mesma regra do agendador)
        if (!entitlement.siegEntitled(companyId)) {
            throw new ConflictException("error.capture.priority.not.in.plan",
                    messageService.getMessage("error.capture.priority.not.in.plan"));
        }
        // 5-8 and the INSERT: under the firm's named lock, in one transaction (CapturePriorityWriter)
        String scope = platform ? CapturePriorityRequest.SCOPE_PLATFORM : CapturePriorityRequest.SCOPE_FIRM;
        CapturePriorityWriter.Opened opened;
        try {
            opened = writer.open(companyId, firmId, user.getId(), scope, auditLogin(user), now());
        } catch (DataIntegrityViolationException e) {
            if (!DuplicateKeys.isDuplicateKey(e)) {
                throw e;
            }
            // two clicks at the same time without the lock: the other one opened it - answer with that one
            return response(repository.findByOpenKey(companyId.toString()).orElseThrow(this::companyNotFound));
        }
        if (opened.created()) {
            log.info("capture.priority.requested company={} firm={} by={} scope={}", companyId, firmId, user.getId(),
                    platform ? "platform" : "firm");
        }
        return response(opened.request());
    }

    /** 202 {requestId, status, position, estimatedAt}: a posição na fila de prioridade (0 = a próxima) e a estimativa. */
    CapturePriorityResponseDTO response(CapturePriorityRequest request) {
        List<CapturePriorityRequest> open = repository.findOpenOrdered();
        int position = 0;
        for (int i = 0; i < open.size(); i++) {
            if (open.get(i).getId().equals(request.getId())) {
                position = i;
                break;
            }
        }
        CaptureQueueClock queueClock = CaptureQueueClock.of(syncProperties.getCron(), now(), ZoneId.systemDefault());
        OffsetDateTime estimatedAt = queueClock == null ? null : offset(queueClock.afterRounds(
                CaptureQueueMath.roundsForPriorityPosition(position, syncProperties.getBatchSize(), properties.getSlotsPerTick())));
        return new CapturePriorityResponseDTO(request.getId(), request.getStatus().name(), position, estimatedAt);
    }

    // ------------------------------------------------------------------ the scheduler's side (FiscalXmlSyncScheduler)

    /**
     * No início da rodada: os abertos há mais de {@code expire-hours} viram EXPIRED; os de empresa que a rodada não
     * atende (escritório bloqueado, perdeu o direito) viram CANCELED (CP-RN-18). Devolve os que seguem abertos, do mais
     * antigo para o mais novo.
     */
    public List<CapturePriorityRequest> prepareTick(Set<UUID> servedCompanyIds) {
        List<CapturePriorityRequest> kept = new ArrayList<>();
        LocalDateTime now = now();
        LocalDateTime expireBefore = now.minusHours(properties.getExpireHours());
        for (CapturePriorityRequest request : repository.findOpenOrdered()) {
            if (request.getRequestedAt().isBefore(expireBefore)) {
                close(request, CapturePriorityStatus.EXPIRED, null);
            } else if (!servedCompanyIds.contains(request.getCompanyId())) {
                close(request, CapturePriorityStatus.CANCELED, null);
            } else {
                kept.add(request);
            }
        }
        return kept;
    }

    /** Vagas de prioridade desta rodada (CP-RN-15); com lote 1, alterna com a fila normal. */
    public int prioritySlots(int batchSize, int open) {
        if (!properties.isEnabled()) {
            return 0;
        }
        int slots = CaptureQueueMath.prioritySlots(batchSize, properties.getSlotsPerTick(), open, lastTickWasPriority.get());
        if (Math.max(1, batchSize) == 1 && open > 0) {
            lastTickWasPriority.set(slots > 0);
        }
        return slots;
    }

    public void reserved(CapturePriorityRequest request) {
        try {
            repository.markReserved(request.getId());
        } catch (RuntimeException e) {
            log.warn("capture.priority.reserve.failed company={} request={}: {}", request.getCompanyId(), request.getId(),
                    e.getClass().getSimpleName());
        }
    }

    /** A tentativa terminou (deu certo ou não): o pedido foi atendido e a empresa volta ao fim da fila normal. */
    public void served(CapturePriorityRequest request) {
        close(request, CapturePriorityStatus.SERVED, now());
    }

    private void close(CapturePriorityRequest request, CapturePriorityStatus status, LocalDateTime servedAt) {
        try {
            if (repository.close(request.getId(), status, servedAt) == 1) {
                log.info("capture.priority.served company={} request={} outcome={}", request.getCompanyId(), request.getId(), status);
            }
        } catch (RuntimeException e) {
            log.warn("capture.priority.close.failed company={} request={} outcome={}: {}", request.getCompanyId(),
                    request.getId(), status, e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ cleanup (CP-RN-20)

    @Scheduled(cron = "${taxify.capture-priority.purge-cron:0 45 3 * * *}", zone = "America/Recife")
    public void purgeTick() {
        if (!properties.isPurgeEnabled() || !purging.compareAndSet(false, true)) {
            return;
        }
        try {
            int removed = purge();
            if (removed > 0) {
                log.info("capture.priority.purged count={}", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Capture priority purge failed - tried again on the next run: {}", e.getClass().getSimpleName());
        } finally {
            purging.set(false);
        }
    }

    /** Apaga os pedidos fechados com requested_at de mais de {@code retention-days} dias. */
    public int purge() {
        return repository.deleteRequestedBefore(now().minusDays(properties.getRetentionDays()));
    }

    // ------------------------------------------------------------------ helpers

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    static OffsetDateTime offset(LocalDateTime recife) {
        return recife == null ? null : recife.atZone(ClockConfig.RECIFE).toOffsetDateTime();
    }

    private static String auditLogin(User user) {
        String login = user.getLogin() == null ? "system" : user.getLogin();
        return login.length() > 50 ? login.substring(0, 50) : login;
    }

    private ResourceNotFoundException companyNotFound() {
        return new ResourceNotFoundException("error.Company.not.found", messageService.getMessage("error.Company.not.found"));
    }
}
