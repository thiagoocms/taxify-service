package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Enforces the tenant boundary: every Company and User belongs to exactly one
 * AccountingFirm, and this makes sure the current request's user only ever
 * reads/writes data belonging to their own firm.
 * <p>
 * On top of that, a user who isn't ADMIN only sees the companies they're associated
 * with through {@link UserCompany} - an ADMIN sees every company of their firm.
 * <p>
 * {@link CurrentUserService#getCurrentUser()} being empty means a trusted system context
 * (a scheduler/background job, not an end-user request) - no restriction is applied there,
 * mirroring the convention CurrentUserService itself already documents.
 */
@Component
public class TenantAccessService {

    private final CurrentUserService currentUserService;
    private final MessageService messageService;
    private final UserCompanyRepository userCompanyRepository;

    public TenantAccessService(CurrentUserService currentUserService, MessageService messageService,
                               UserCompanyRepository userCompanyRepository) {
        this.currentUserService = currentUserService;
        this.messageService = messageService;
        this.userCompanyRepository = userCompanyRepository;
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

    /**
     * The current user's id when their access has to be limited to their associated
     * companies; empty for ADMIN and for system context (no restriction).
     */
    public Optional<UUID> getRestrictedUserId() {
        return currentUserService.getCurrentUser()
                .filter(user -> user.getProfile() != UserProfileEnum.ADMIN)
                .map(User::getId);
    }

    public boolean canAccessCompany(UUID companyId) {
        return getRestrictedUserId()
                .map(userId -> userCompanyRepository.existsByUser_IdAndCompany_IdAndDeletedIsFalse(userId, companyId))
                .orElse(true);
    }

    /**
     * Same firm AND (ADMIN or associated to the company). Like {@link #assertSameFirm},
     * a company the user can't see answers as not found.
     */
    public void assertCompanyAccess(Company company) {
        assertSameFirm(company.getAccountingFirm().getId());
        if (!canAccessCompany(company.getId())) {
            throw new ResourceNotFoundException(messageService.getMessage("error.Company.not.found"));
        }
    }

    /**
     * Adds "company is associated to the current user" to a list query, for non-ADMIN
     * users - {@code companyPath} points from the queried entity to its Company (the root
     * itself when listing companies).
     */
    public <E> Specification<E> restrictToUserCompanies(Specification<E> spec, Function<Root<E>, Path<?>> companyPath) {
        Optional<UUID> userId = getRestrictedUserId();
        if (userId.isEmpty()) {
            return spec;
        }
        Specification<E> restriction = (root, query, builder) -> {
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<UserCompany> userCompany = subquery.from(UserCompany.class);
            subquery.select(builder.literal(1))
                    .where(
                            builder.equal(userCompany.get("company"), companyPath.apply(root)),
                            builder.equal(userCompany.get("user").get("id"), userId.get()),
                            builder.isFalse(userCompany.get("deleted"))
                    );
            return builder.exists(subquery);
        };
        return spec == null ? restriction : spec.and(restriction);
    }
}
