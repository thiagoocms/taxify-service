package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.pendency.Pendency;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyStatus;
import br.com.cshub.taxify.enumerated.PendencyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PendencyRepository extends AbstractJpaRepository<Pendency, UUID> {

    List<Pendency> findByCompany_IdAndTypeIn(UUID companyId, Collection<PendencyType> types);

    /** The screen's list: most severe first, then the oldest. Every filter is optional (null = any). */
    @Query(value = "select p from Pendency p join fetch p.company c where c.id in :companyIds and p.deleted = false"
            + " and (:status is null or p.status = :status)"
            + " and (:severity is null or p.severity = :severity)"
            + " and (:type is null or p.type = :type)"
            + " and (:competence is null or p.competence = :competence)"
            + " order by case p.severity"
            + "   when br.com.cshub.taxify.enumerated.PendencySeverity.ALTA then 0"
            + "   when br.com.cshub.taxify.enumerated.PendencySeverity.MEDIA then 1"
            + "   when br.com.cshub.taxify.enumerated.PendencySeverity.BAIXA then 2 else 3 end,"
            + " p.firstSeenAt asc",
            countQuery = "select count(p) from Pendency p where p.company.id in :companyIds and p.deleted = false"
                    + " and (:status is null or p.status = :status)"
                    + " and (:severity is null or p.severity = :severity)"
                    + " and (:type is null or p.type = :type)"
                    + " and (:competence is null or p.competence = :competence)")
    Page<Pendency> search(@Param("companyIds") Collection<UUID> companyIds, @Param("status") PendencyStatus status,
                          @Param("severity") PendencySeverity severity, @Param("type") PendencyType type,
                          @Param("competence") String competence, Pageable pageable);

    /** Open pendencies per type and severity - the counters. Row: type, severity, count. */
    @Query("select p.type, p.severity, count(p) from Pendency p where p.company.id in :companyIds and p.deleted = false"
            + " and p.status = br.com.cshub.taxify.enumerated.PendencyStatus.ABERTA group by p.type, p.severity")
    List<Object[]> countOpen(@Param("companyIds") Collection<UUID> companyIds);
}
