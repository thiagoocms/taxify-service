package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.dto.integration.FirmIntegrationStatusDTO;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import br.com.cshub.taxify.repository.FirmIntegrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single place every integration (SIEG, Acessórias, Conta Azul) reads and writes its
 * per-firm credentials and sync bookkeeping. Credentials never go back to the front-end
 * whole - only a masked hint (see {@link #toStatus}).
 */
@Service
public class FirmIntegrationService {

    /** Integrations configured by pasting an API key; the rest (Conta Azul) connect via OAuth. */
    public static final Set<IntegrationType> API_KEY_TYPES = EnumSet.of(IntegrationType.SIEG, IntegrationType.ACESSORIAS);

    private final FirmIntegrationRepository repository;
    private final AccountingFirmRepository accountingFirmRepository;
    private final CurrentUserService currentUserService;
    private final MessageService messageService;

    public FirmIntegrationService(FirmIntegrationRepository repository, AccountingFirmRepository accountingFirmRepository,
                                  CurrentUserService currentUserService, MessageService messageService) {
        this.repository = repository;
        this.accountingFirmRepository = accountingFirmRepository;
        this.currentUserService = currentUserService;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public Optional<FirmIntegration> find(UUID accountingFirmId, IntegrationType type) {
        return repository.findFirstByAccountingFirm_IdAndTypeAndDeletedIsFalse(accountingFirmId, type);
    }

    /** API key of a key-based integration, or null when the firm hasn't configured it. */
    @Transactional(readOnly = true)
    public String getApiKey(UUID accountingFirmId, IntegrationType type) {
        return find(accountingFirmId, type).map(FirmIntegration::getApiKey).filter(key -> !key.isBlank()).orElse(null);
    }

    @Transactional(readOnly = true)
    public LocalDateTime getLastSyncAt(UUID accountingFirmId, IntegrationType type) {
        return find(accountingFirmId, type).map(FirmIntegration::getLastSyncAt).orElse(null);
    }

    /**
     * Separate transaction: a sync's bookkeeping must stick even when the caller's own work
     * runs (and may roll back) in another one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSynced(UUID accountingFirmId, IntegrationType type, LocalDateTime startedAt) {
        FirmIntegration integration = getOrCreate(accountingFirmId, type);
        integration.setLastSyncAt(startedAt);
        repository.save(integration);
    }

    /** Active firms' integrations of one type that have credentials - for the schedulers. */
    @Transactional(readOnly = true)
    public List<FirmIntegration> findConfigured(IntegrationType type) {
        return repository.findByTypeAndDeletedIsFalseAndAccountingFirm_DeletedIsFalse(type).stream()
                .filter(FirmIntegration::isConfigured)
                .toList();
    }

    @Transactional
    public FirmIntegration getOrCreate(UUID accountingFirmId, IntegrationType type) {
        return find(accountingFirmId, type).orElseGet(() -> {
            AccountingFirm firm = accountingFirmRepository.findById(accountingFirmId)
                    .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.accountingFirm.not.found")));
            return repository.save(new FirmIntegration(firm, type));
        });
    }

    @Transactional
    public FirmIntegration save(FirmIntegration integration) {
        return repository.save(integration);
    }

    // ------------------------------------------------------------ current user's firm (REST)

    public UUID currentFirmId() {
        return currentUserService.getCurrentUser()
                .map(user -> user.getAccountingFirm().getId())
                .orElseThrow(() -> new ResourceNotFoundException(messageService.getMessage("error.accountingFirm.not.found")));
    }

    /** One status per integration type, configured or not, so the screen can list them all. */
    @Transactional(readOnly = true)
    public List<FirmIntegrationStatusDTO> listForCurrentFirm() {
        Map<IntegrationType, FirmIntegration> byType = repository.findByAccountingFirm_IdAndDeletedIsFalse(currentFirmId()).stream()
                .collect(Collectors.toMap(FirmIntegration::getType, Function.identity(), (a, b) -> a));
        return Arrays.stream(IntegrationType.values())
                .map(type -> toStatus(type, byType.get(type)))
                .toList();
    }

    /** Sets (or, when blank, clears) the API key of a key-based integration of the current firm. */
    @Transactional
    public FirmIntegrationStatusDTO saveApiKeyForCurrentFirm(IntegrationType type, String apiKey) {
        if (!API_KEY_TYPES.contains(type)) {
            throw new BadRequestException(messageService.getMessage("error.integration.api.key.not.supported"));
        }
        FirmIntegration integration = getOrCreate(currentFirmId(), type);
        integration.setApiKey(apiKey == null || apiKey.isBlank() ? null : apiKey.strip());
        return toStatus(type, repository.save(integration));
    }

    static FirmIntegrationStatusDTO toStatus(IntegrationType type, FirmIntegration integration) {
        boolean apiKeyBased = API_KEY_TYPES.contains(type);
        if (integration == null) {
            return new FirmIntegrationStatusDTO(type, apiKeyBased, false, null, null, null);
        }
        return new FirmIntegrationStatusDTO(type, apiKeyBased, integration.isConfigured(),
                maskKey(integration.getApiKey()), integration.getConnectedAt(), integration.getLastSyncAt());
    }

    /** "••••1234" - enough to recognize which key is set without exposing it. */
    static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.strip();
        return "••••" + (trimmed.length() > 8 ? trimmed.substring(trimmed.length() - 4) : "");
    }
}
