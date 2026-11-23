package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.audit.AuditRun;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditRunRepository extends AbstractJpaRepository<AuditRun, UUID> {

    Optional<AuditRun> findByCompany_IdAndCompetence(UUID companyId, String competence);
}
