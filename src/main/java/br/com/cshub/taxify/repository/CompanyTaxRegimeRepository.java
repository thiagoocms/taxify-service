package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.company.CompanyTaxRegime;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CompanyTaxRegimeRepository extends AbstractJpaRepository<CompanyTaxRegime, UUID> {

    /** A company's periods, oldest first ("since always" before any dated one). */
    @Query("select r from CompanyTaxRegime r where r.company.id = :companyId and r.deleted = false"
            + " order by case when r.validFrom is null then 0 else 1 end, r.validFrom")
    List<CompanyTaxRegime> history(@Param("companyId") UUID companyId);

    /** Same, for several companies at once (the firm-wide assessment). */
    @Query("select r from CompanyTaxRegime r where r.company.id in :companyIds and r.deleted = false"
            + " order by r.company.id, case when r.validFrom is null then 0 else 1 end, r.validFrom")
    List<CompanyTaxRegime> historyOf(@Param("companyIds") Collection<UUID> companyIds);

    boolean existsByCompany_IdAndDeletedIsFalse(UUID companyId);
}
