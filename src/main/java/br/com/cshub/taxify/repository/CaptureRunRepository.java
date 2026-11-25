package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.pendency.CaptureRun;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CaptureRunRepository extends AbstractJpaRepository<CaptureRun, UUID> {

    /** Runs since {@code since} per company, service and status. Row: companyId, service, status, count. */
    @Query("select r.company.id, r.service, r.status, count(r) from CaptureRun r where r.company.id in :companyIds"
            + " and r.startedAt >= :since group by r.company.id, r.service, r.status")
    List<Object[]> countSince(@Param("companyIds") Collection<UUID> companyIds, @Param("since") LocalDateTime since);

    @Modifying
    @Transactional
    @Query("delete from CaptureRun r where r.startedAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);

    /** The newest run of a service per company - the integrations' health of a collector without a control table. */
    @Query("select r from CaptureRun r join fetch r.company where r.service = :service and r.company.id in :companyIds"
            + " and r.startedAt = (select max(o.startedAt) from CaptureRun o where o.company = r.company and o.service = :service)")
    List<CaptureRun> findLatestOf(@Param("companyIds") Collection<UUID> companyIds, @Param("service") String service);
}
