package br.com.cshub.taxify.common;

import br.com.cshub.taxify.exception.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Enforces the tenant boundary: every Company and User belongs to exactly one
 * AccountingFirm, and this makes sure the current request's user only ever
 * reads/writes data belonging to their own firm.
 * <p>
 * {@link CurrentUserService#getCurrentUser()} being empty means a trusted system context
 * (a scheduler/background job, not an end-user request) - no restriction is applied there,
 * mirroring the convention CurrentUserService itself already documents.
 */
@Component
public class TenantAccessService {

    private final CurrentUserService currentUserService;
    private final MessageService messageService;

    public TenantAccessService(CurrentUserService currentUserService, MessageService messageService) {
        this.currentUserService = currentUserService;
        this.messageService = messageService;
    }

    /** Empty Optional = no restriction (system/background context). */
    public Optional<UUID> getCurrentAccountingFirmId() {
        return currentUserService.getCurrentUser().map(user -> user.getAccountingFirm().getId());
    }

    public boolean isSameFirm(UUID accountingFirmId) {
        return getCurrentAccountingFirmId()
                .map(id -> id.equals(accountingFirmId))
                .orElse(true);
    }

    /**
     * Throws ResourceNotFoundException (not Forbidden) when the current user belongs to a
     * different firm - data from another accounting firm should look like it doesn't
     * exist at all, not like a permission was denied.
     */
    public void assertSameFirm(UUID accountingFirmId) {
        if (!isSameFirm(accountingFirmId)) {
            throw new ResourceNotFoundException(messageService.getMessage("error.Company.not.found"));
        }
    }
}
