package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.billing.BillingCharge;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.ChargeStatus;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingChargeRepository extends AbstractJpaRepository<BillingCharge, UUID> {

    Optional<BillingCharge> findFirstByCompany_IdAndSourceAndExternalId(UUID companyId, BillingProviderType source, String externalId);

    List<BillingCharge> findByCompany_IdAndDeletedIsFalseOrderByDueDateDesc(UUID companyId);

    /** Most recent non-canceled charge due up to {@code dueUntil} - what the current fee is taken from. */
    Optional<BillingCharge> findFirstByCompany_IdAndSourceAndStatusNotInAndDueDateLessThanEqualAndDeletedIsFalseOrderByDueDateDesc(
            UUID companyId, BillingProviderType source, Collection<ChargeStatus> excluded, LocalDate dueUntil);
}
