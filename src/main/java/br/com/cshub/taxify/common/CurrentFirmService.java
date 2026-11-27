package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.repository.AccountingFirmRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The accounting firm of the logged-in user, for endpoints that act on "my firm" (imports,
 * integrations, billing). The one place that resolves it, so every caller fails the same
 * way - {@code error.accountingFirm.not.found} - when there's no user or firm behind the
 * request (e.g. a scheduler thread calling a REST-only path by mistake).
 */
@Service
public class CurrentFirmService {

    private final TenantAccessService tenantAccessService;
    private final AccountingFirmRepository accountingFirmRepository;
    private final MessageService messageService;

    public CurrentFirmService(TenantAccessService tenantAccessService, AccountingFirmRepository accountingFirmRepository,
                              MessageService messageService) {
        this.tenantAccessService = tenantAccessService;
        this.accountingFirmRepository = accountingFirmRepository;
        this.messageService = messageService;
    }

    public UUID requireId() {
        return tenantAccessService.getCurrentAccountingFirmId().orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public AccountingFirm require() {
        return accountingFirmRepository.findById(requireId()).orElseThrow(this::notFound);
    }

    /** A firm by id - for callers that already hold one (schedulers), with the same error as above. */
    @Transactional(readOnly = true)
    public AccountingFirm require(UUID accountingFirmId) {
        return accountingFirmRepository.findById(accountingFirmId).orElseThrow(this::notFound);
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("error.accountingFirm.not.found", messageService.getMessage("error.accountingFirm.not.found"));
    }
}
