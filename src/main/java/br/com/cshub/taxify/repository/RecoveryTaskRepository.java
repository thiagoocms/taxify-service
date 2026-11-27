package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.firmaccess.RecoveryTask;
import br.com.cshub.taxify.enumerated.RecoveryTaskSource;
import br.com.cshub.taxify.enumerated.RecoveryTaskStatus;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecoveryTaskRepository extends AbstractJpaRepository<RecoveryTask, UUID> {

    /**
     * O pedaço de um escopo, fonte e mês. Sem o filtro de {@code deleted} de propósito: espelha a chave única
     * uk_recovery_tasks (scope_id, source, period_start), que vale para todas as linhas - o upsert reabre o existente.
     */
    Optional<RecoveryTask> findByScopeIdAndSourceAndPeriodStart(UUID scopeId, RecoveryTaskSource source, LocalDate periodStart);

    /** O próximo pedaço de uma empresa, do mais antigo para o mais novo. */
    Optional<RecoveryTask> findFirstByCompanyIdAndStatusAndSourceInAndDeletedIsFalseOrderByPeriodStartAscSourceAsc(
            UUID companyId, RecoveryTaskStatus status, Collection<RecoveryTaskSource> sources);

    List<RecoveryTask> findByAccountingFirmIdAndStatusAndSourceAndDeletedIsFalse(UUID accountingFirmId, RecoveryTaskStatus status,
                                                                                 RecoveryTaskSource source);

    List<RecoveryTask> findByStatusAndSourceInAndDeletedIsFalse(RecoveryTaskStatus status, Collection<RecoveryTaskSource> sources);

    List<RecoveryTask> findByAccountingFirmIdAndStatusAndDeletedIsFalse(UUID accountingFirmId, RecoveryTaskStatus status);

    List<RecoveryTask> findByAccountingFirmIdAndPeriodStartBetweenAndDeletedIsFalse(UUID accountingFirmId, LocalDate from,
                                                                                    LocalDate to);

    long countByAccountingFirmIdAndStatusAndDeletedIsFalse(UUID accountingFirmId, RecoveryTaskStatus status);
}
