package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.search.AbstractsSpecification;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.io.Serializable;
import java.util.function.Function;

/**
 * CRUD of something that belongs to a client company (subscriptions, fiscal rules, charges...):
 * reads and writes are limited to the current user's firm and, for non-admins, to the
 * companies in their portfolio - declared once here instead of in each service. A subclass
 * only says how to reach the company.
 */
public abstract class CompanyScopedCrudService<E extends AbstractAuditingEntity, ID extends Serializable, M, F>
        extends AbstractCrudService<E, ID, M, F> {

    protected final TenantAccessService tenantAccessService;
    private final Function<E, Company> companyOf;
    private final Function<Root<E>, Path<?>> companyPath;

    /**
     * @param companyOf   the entity's company (checked on every single-record access)
     * @param companyPath the company in a query, e.g. {@code root -> root.get("company")}
     */
    protected CompanyScopedCrudService(AbstractJpaRepository<E, ID> repository, AbstractMapper<E, M> mapper,
                                       AbstractCrudValidation<E, ID> validation, MessageService messageService,
                                       AbstractsSpecification<E> specification, TenantAccessService tenantAccessService,
                                       Function<E, Company> companyOf, Function<Root<E>, Path<?>> companyPath) {
        super(repository, mapper, validation, messageService, specification);
        this.tenantAccessService = tenantAccessService;
        this.companyOf = companyOf;
        this.companyPath = companyPath;
    }

    @Override
    protected void checkAccess(E entity) {
        tenantAccessService.assertCompanyAccess(companyOf.apply(entity));
    }

    @Override
    protected Specification<E> restrictQuery(Specification<E> spec) {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        if (firmId.isEmpty()) {
            return spec;
        }
        Specification<E> sameFirm = (root, query, builder) ->
                builder.equal(companyPath.apply(root).get("accountingFirm").get("id"), firmId.get());
        return tenantAccessService.restrictToUserCompanies(spec == null ? sameFirm : spec.and(sameFirm), companyPath);
    }
}
