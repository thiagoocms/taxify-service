package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.capture.CapturePriorityRequest;
import br.com.cshub.taxify.enumerated.CapturePriorityStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pedidos de "Sincronizar agora" (CP-RN-15 a CP-RN-20). Horas de America/Recife em requested_at e served_at. */
@Repository
public interface CapturePriorityRequestRepository extends AbstractJpaRepository<CapturePriorityRequest, UUID> {

    /** O pedido aberto da empresa (chave única open_key). */
    Optional<CapturePriorityRequest> findByOpenKey(String openKey);

    /** Todos os abertos, do mais antigo para o mais novo - a fila de prioridade (plataforma inteira). */
    @Query("select r from CapturePriorityRequest r where r.deleted = false and r.openKey is not null"
            + " order by r.requestedAt asc, r.id asc")
    List<CapturePriorityRequest> findOpenOrdered();

    @Query("select r from CapturePriorityRequest r where r.deleted = false and r.openKey is not null and r.companyId in :companyIds")
    List<CapturePriorityRequest> findOpenByCompanyIds(@Param("companyIds") Collection<UUID> companyIds);

    @Query("select count(r) from CapturePriorityRequest r where r.deleted = false and r.openKey is not null"
            + " and r.accountingFirmId = :firmId")
    long countOpenOfFirm(@Param("firmId") UUID firmId);

    /** Pedidos do escritório num dia civil [from, to) - o teto diário. */
    @Query("select count(r) from CapturePriorityRequest r where r.deleted = false and r.accountingFirmId = :firmId"
            + " and r.requestedAt >= :from and r.requestedAt < :to")
    long countOfFirmBetween(@Param("firmId") UUID firmId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** O último pedido atendido da empresa - o cooldown. */
    Optional<CapturePriorityRequest> findFirstByCompanyIdAndStatusAndDeletedIsFalseOrderByServedAtDesc(UUID companyId,
                                                                                                       CapturePriorityStatus status);

    /** PENDING → RESERVED (um RESERVED que ficou de uma rodada interrompida também pode ser pego de novo). */
    @Modifying
    @Transactional
    @Query("update CapturePriorityRequest r set r.status = br.com.cshub.taxify.enumerated.CapturePriorityStatus.RESERVED"
            + " where r.id = :id and r.openKey is not null")
    int markReserved(@Param("id") UUID id);

    /** Fecha o pedido aberto: SERVED, CANCELED ou EXPIRED; a chave aberta volta a nulo. 0 = já estava fechado. */
    @Modifying
    @Transactional
    @Query("update CapturePriorityRequest r set r.status = :status, r.servedAt = :at, r.openKey = null"
            + " where r.id = :id and r.openKey is not null")
    int close(@Param("id") UUID id, @Param("status") CapturePriorityStatus status, @Param("at") LocalDateTime at);

    /** Lock nomeado do MySQL (NamedLocks) por escritório: 1 pegou, 0 tempo esgotado. Nativo e sem flush. */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT GET_LOCK(:name, :seconds)", nativeQuery = true)
    Long getLock(@Param("name") String name, @Param("seconds") int seconds);

    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT RELEASE_LOCK(:name)", nativeQuery = true)
    Long releaseLock(@Param("name") String name);

    /**
     * O pedido novo, aberto ({@code open_key = company_id}), na transação de quem chama (CapturePriorityWriter, sob o
     * lock do escritório) e sem passar pelo contexto de persistência. Sem o lock, o segundo de dois cliques simultâneos
     * bate na chave única e quem chamou lê o pedido aberto.
     */
    @Modifying
    @Query(value = "INSERT INTO capture_priority_requests (id, company_id, accounting_firm_id, requested_by, requested_scope,"
            + " requested_at, status, open_key, deleted, created_by, created_date)"
            + " VALUES (:id, :companyId, :firmId, :requestedBy, :scope, :at, 'PENDING', :companyId, 0, :createdBy, :at)",
            nativeQuery = true)
    int insertOpen(@Param("id") String id, @Param("companyId") String companyId, @Param("firmId") String firmId,
                   @Param("requestedBy") String requestedBy, @Param("scope") String scope, @Param("at") LocalDateTime at,
                   @Param("createdBy") String createdBy);

    /** Limpeza (CP-RN-20): apaga fisicamente os pedidos fechados com requested_at antes de {@code before}. */
    @Modifying
    @Transactional
    @Query("delete from CapturePriorityRequest r where r.requestedAt < :before and r.openKey is null")
    int deleteRequestedBefore(@Param("before") LocalDateTime before);
}
