package br.com.cshub.taxify.common;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.search.AbstractsSpecification;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.io.Serializable;
import java.util.UUID;
import java.util.function.Function;

/**
 * CRUD of something that belongs to the accounting firm itself (users, plans, the firm):
 * limited to the current user's firm. Something without a firm (legacy rows) is visible to
 * nobody but background jobs.
 */
public abstract class FirmScopedCrudService<E extends AbstractAuditingEntity, ID extends Serializable, M, F>
        extends AbstractCrudService<E, ID, M, F> {

    protected final TenantAccessService tenantAccessService;
    private final Function<E, UUID> firmIdOf;
    private final Function<Root<E>, Path<?>> firmIdPath;

    /**
     * @param firmIdOf   the entity's firm id (null = no firm)
     * @param firmIdPath the firm id in a query, e.g. {@code root -> root.get("accountingFirm").get("id")}
     */
    protected FirmScopedCrudService(AbstractJpaRepository<E, ID> repository, AbstractMapper<E, M> mapper,
                                    AbstractCrudValidation<E, ID> validation, MessageService messageService,
                                    AbstractsSpecification<E> specification, TenantAccessService tenantAccessService,
                                    Function<E, UUID> firmIdOf, Function<Root<E>, Path<?>> firmIdPath) {
        super(repository, mapper, validation, messageService, specification);
        this.tenantAccessService = tenantAccessService;
        this.firmIdOf = firmIdOf;
        this.firmIdPath = firmIdPath;
    }

    @Override
    protected void checkAccess(E entity) {
        tenantAccessService.assertSameFirm(firmIdOf.apply(entity));
    }

    @Override
    protected Specification<E> restrictQuery(Specification<E> spec) {
        var firmId = tenantAccessService.getCurrentAccountingFirmId();
        if (firmId.isEmpty()) {
            return spec;
        }
        Specification<E> sameFirm = (root, query, builder) -> builder.equal(firmIdPath.apply(root), firmId.get());
        return spec == null ? sameFirm : spec.and(sameFirm);
    }
}
