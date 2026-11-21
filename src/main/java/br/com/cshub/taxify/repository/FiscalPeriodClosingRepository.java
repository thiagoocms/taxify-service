package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.assessment.FiscalPeriodClosing;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FiscalPeriodClosingRepository extends AbstractJpaRepository<FiscalPeriodClosing, UUID> {

    Optional<FiscalPeriodClosing> findFirstByCompany_IdAndPeriodAndDeletedIsFalse(UUID companyId, LocalDate period);

    List<FiscalPeriodClosing> findByCompany_IdInAndPeriodAndDeletedIsFalse(Collection<UUID> companyIds, LocalDate period);
}
