package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.audit.AuditRule;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditRuleRepository extends AbstractJpaRepository<AuditRule, UUID> {

    Optional<AuditRule> findByCode(String code);
}
