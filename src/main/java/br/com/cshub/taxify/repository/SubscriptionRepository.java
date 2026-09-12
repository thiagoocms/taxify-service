package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.subscription.Subscription;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends AbstractJpaRepository<Subscription, UUID> {

    // Overlap check: entity.startDate <= requestedEndDate AND entity.endDate >= requestedStartDate
    boolean existsByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID companyId, LocalDate requestedEndDate, LocalDate requestedStartDate);

    boolean existsByCompanyIdAndDeletedIsFalseAndIdNotAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID companyId, UUID excludeId, LocalDate requestedEndDate, LocalDate requestedStartDate);

    // "Vigente": entity.startDate <= referenceDate <= entity.endDate. Pass the same date (today) for both.
    Optional<Subscription> findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
            UUID companyId, LocalDate referenceDateForStart, LocalDate referenceDateForEnd);
}
