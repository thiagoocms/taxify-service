package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.NamedLocks;
import br.com.cshub.taxify.config.CapturePriorityProperties;
import br.com.cshub.taxify.config.ClockConfig;
import br.com.cshub.taxify.domain.capture.CapturePriorityRequest;
import br.com.cshub.taxify.enumerated.CapturePriorityStatus;
import br.com.cshub.taxify.exception.ServiceUnavailableException;
import br.com.cshub.taxify.exception.TooManyRequestsException;
import br.com.cshub.taxify.repository.CapturePriorityRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A parte de gravação do "Sincronizar agora" (CP-RN-17 #5 a #8 e o INSERT) numa transação só, sob um lock nomeado do
 * MySQL por escritório ({@link NamedLocks}, como o teto por IP do "Esqueci a senha"): o pedido aberto da empresa, o
 * cooldown, os 5 abertos e os 10 do dia são contados e o pedido é gravado sem outra instância ou outro clique no meio -
 * os tetos não são furados por pedidos simultâneos. O lock é pego antes de qualquer leitura (a fotografia da transação
 * já enxerga o último pedido gravado) e solto depois do commit.
 */
@Component
public class CapturePriorityWriter {

    private static final Logger log = LoggerFactory.getLogger(CapturePriorityWriter.class);
    static final String LOCK_PREFIX = "capprio:";
    static final int LOCK_SECONDS = 5;

    private final CapturePriorityRequestRepository repository;
    private final CapturePriorityProperties properties;
    private final MessageService messageService;

    public CapturePriorityWriter(CapturePriorityRequestRepository repository, CapturePriorityProperties properties,
                                 MessageService messageService) {
        this.repository = repository;
        this.properties = properties;
        this.messageService = messageService;
    }

    /**
     * O pedido aberto da empresa (o que já existia - idempotente, nada é contado - ou o novo).
     *
     * @throws TooManyRequestsException    cooldown, teto de abertos ou teto do dia
     * @throws ServiceUnavailableException o lock do escritório não saiu no tempo (outra gravação demorando)
     */
    @Transactional
    public Opened open(UUID companyId, UUID firmId, UUID requestedBy, String scope, String createdBy,
                                       LocalDateTime now) {
        String name = NamedLocks.name(LOCK_PREFIX, firmId.toString());
        NamedLocks.Held lock = NamedLocks.untilTransactionEnds(() -> repository.getLock(name, LOCK_SECONDS),
                () -> releaseQuietly(name));
        if (lock == null) {
            log.info("capture.priority.busy firm={}", firmId);
            throw new ServiceUnavailableException("error.capture.priority.unavailable",
                    messageService.getMessage("error.capture.priority.unavailable"));
        }
        try (NamedLocks.Held held = lock) {
            // 5: idempotent - the company's open request, nothing counted
            Optional<CapturePriorityRequest> open = repository.findByOpenKey(companyId.toString());
            if (open.isPresent()) {
                return new Opened(open.get(), false);
            }
            // 6: cooldown since the last one served
            var lastServed = repository.findFirstByCompanyIdAndStatusAndDeletedIsFalseOrderByServedAtDesc(companyId,
                    CapturePriorityStatus.SERVED);
            if (lastServed.isPresent() && lastServed.get().getServedAt() != null) {
                LocalDateTime retryAt = lastServed.get().getServedAt().plusMinutes(properties.getCooldownMinutes());
                if (retryAt.isAfter(now)) {
                    throw new TooManyRequestsException("error.capture.priority.cooldown",
                            messageService.getMessage("error.capture.priority.cooldown"), Map.of("retryAt", offset(retryAt)));
                }
            }
            // 7: the firm's open requests
            if (repository.countOpenOfFirm(firmId) >= properties.getMaxOpenPerFirm()) {
                throw new TooManyRequestsException("error.capture.priority.firm.open.limit",
                        messageService.getMessage("error.capture.priority.firm.open.limit"),
                        Map.of("limit", properties.getMaxOpenPerFirm()));
            }
            // 8: the cap of Recife's civil day
            LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
            LocalDateTime nextDay = dayStart.plusDays(1);
            if (repository.countOfFirmBetween(firmId, dayStart, nextDay) >= properties.getPerFirmPerDay()) {
                Map<String, Object> extras = new LinkedHashMap<>();
                extras.put("limit", properties.getPerFirmPerDay());
                extras.put("retryAt", offset(nextDay));
                throw new TooManyRequestsException("error.capture.priority.firm.daily.limit",
                        messageService.getMessage("error.capture.priority.firm.daily.limit"), extras);
            }
            String id = UUID.randomUUID().toString();
            repository.insertOpen(id, companyId.toString(), firmId.toString(), requestedBy.toString(), scope, now, createdBy);
            return new Opened(repository.findById(UUID.fromString(id)).orElseThrow(), true);
        }
    }

    /** The open request and whether this call created it (false = it already existed). */
    public record Opened(CapturePriorityRequest request, boolean created) {
    }

    private void releaseQuietly(String name) {
        try {
            repository.releaseLock(name);
        } catch (RuntimeException e) {
            // the MySQL session drops it anyway when the connection is closed
            log.warn("capture.priority.lock.release.failed: {}", e.getClass().getSimpleName());
        }
    }

    static OffsetDateTime offset(LocalDateTime recife) {
        return recife.atZone(ClockConfig.RECIFE).toOffsetDateTime();
    }
}
