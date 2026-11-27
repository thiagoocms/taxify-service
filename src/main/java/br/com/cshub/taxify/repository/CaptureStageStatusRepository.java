package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.capture.CaptureStageStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Resultado das etapas da captura (CP-RN-10). A gravação é um upsert atômico ({@code INSERT ... ON DUPLICATE KEY
 * UPDATE} na chave única (company_id, stage)) numa transação própria: duas instâncias gravando a mesma etapa nunca
 * colidem, e a falha da gravação nunca desfaz o que a captura já gravou. Sem a sintaxe {@code AS new} (o MySQL 5.7
 * local não tem): os valores vêm dos parâmetros.
 */
@Repository
public interface CaptureStageStatusRepository extends AbstractJpaRepository<CaptureStageStatus, UUID> {

    /** Sucesso, com ou sem aviso: zera as falhas seguidas; o último código de falha fica (a tela mostra "resolvida"). */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "INSERT INTO capture_stage_status (id, company_id, stage, last_outcome, last_outcome_at, last_success_at,"
            + " warning_code, warning_count, consecutive_failures, deleted, created_by, created_date)"
            + " VALUES (:id, :companyId, :stage, 'SUCCESS', :now, :now, :warningCode, :warningCount, 0, 0, 'system', :now)"
            + " ON DUPLICATE KEY UPDATE last_outcome = 'SUCCESS', last_outcome_at = :now, last_success_at = :now,"
            + " warning_code = :warningCode, warning_count = :warningCount, consecutive_failures = 0,"
            + " last_modified_by = 'system', last_modified_date = :now", nativeQuery = true)
    int upsertSuccess(@Param("id") String id, @Param("companyId") String companyId, @Param("stage") String stage,
                      @Param("now") LocalDateTime now, @Param("warningCode") String warningCode,
                      @Param("warningCount") Integer warningCount);

    /** Falha: código, status do provedor e uma falha seguida a mais. O aviso anterior fica até o próximo sucesso. */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "INSERT INTO capture_stage_status (id, company_id, stage, last_outcome, last_outcome_at, last_failure_at,"
            + " failure_code, provider_status, consecutive_failures, deleted, created_by, created_date)"
            + " VALUES (:id, :companyId, :stage, 'FAILURE', :now, :now, :failureCode, :providerStatus, 1, 0, 'system', :now)"
            + " ON DUPLICATE KEY UPDATE last_outcome = 'FAILURE', last_outcome_at = :now, last_failure_at = :now,"
            + " failure_code = :failureCode, provider_status = :providerStatus,"
            + " consecutive_failures = consecutive_failures + 1, last_modified_by = 'system', last_modified_date = :now",
            nativeQuery = true)
    int upsertFailure(@Param("id") String id, @Param("companyId") String companyId, @Param("stage") String stage,
                      @Param("now") LocalDateTime now, @Param("failureCode") String failureCode,
                      @Param("providerStatus") String providerStatus);

    /**
     * Fecha uma falha que não vai mais ser tentada (a recuperação sem passo pendente): SUCCESS com o aviso, falhas
     * seguidas a zero, sem mexer em last_success_at. Só mexe na linha que está em FAILURE - 0 = nada a fechar.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "UPDATE capture_stage_status SET last_outcome = 'SUCCESS', last_outcome_at = :now, consecutive_failures = 0,"
            + " warning_code = :warningCode, warning_count = NULL, last_modified_by = 'system', last_modified_date = :now"
            + " WHERE company_id = :companyId AND stage = :stage AND last_outcome = 'FAILURE'", nativeQuery = true)
    int closeFailure(@Param("companyId") String companyId, @Param("stage") String stage, @Param("now") LocalDateTime now,
                     @Param("warningCode") String warningCode);

    /** As etapas das empresas de uma página ou de um escopo - uma consulta só. */
    @Query("select s from CaptureStageStatus s where s.deleted = false and s.companyId in :companyIds")
    List<CaptureStageStatus> findByCompanyIds(@Param("companyIds") Collection<UUID> companyIds);

    @Query("select s from CaptureStageStatus s where s.deleted = false")
    List<CaptureStageStatus> findAllActive();
}
