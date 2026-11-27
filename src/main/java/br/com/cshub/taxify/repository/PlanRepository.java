package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.plan.Plan;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PlanRepository extends AbstractJpaRepository<Plan, UUID> {

    /** (plan id, service) of these plans - the capture panel's entitlement in one query (CP-RN-05). */
    @Query("select p.id, s from Plan p join p.services s where p.id in :planIds")
    List<Object[]> findServicesOf(@Param("planIds") Collection<UUID> planIds);
}
