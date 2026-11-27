package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.pendency.PendencyAlertDigest;
import br.com.cshub.taxify.enumerated.PendencyAlertDigestStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Resumos diários de pendências ALTA (AV-RN-09, AV-RN-10, AV-RN-14). */
@Repository
public interface PendencyAlertDigestRepository extends AbstractJpaRepository<PendencyAlertDigest, UUID> {

    /** Quem já tem resumo (de qualquer situação) neste dia - não ganha outro (AV-RN-09). */
    @Query("select d.userId from PendencyAlertDigest d where d.referenceDate = :day and d.userId in :userIds")
    List<UUID> findUserIdsWithDigestOn(@Param("day") LocalDate day, @Param("userIds") Collection<UUID> userIds);

    /** Último resumo enviado de cada usuário - [user id, max(sent_at)]: a ordem do teto (AV-RN-09). */
    @Query("select d.userId, max(d.sentAt) from PendencyAlertDigest d where d.status = :status and d.userId in :userIds"
            + " group by d.userId")
    List<Object[]> findLastSentAt(@Param("status") PendencyAlertDigestStatus status, @Param("userIds") Collection<UUID> userIds);

    /** Tentativas de envio do dia (SENDING, SENT, FAILED) - o que conta no teto global. */
    long countByReferenceDateAndStatusIn(LocalDate day, Collection<PendencyAlertDigestStatus> statuses);

    /** Um SENDING de um dia anterior (a aplicação caiu no meio) conta como FAILED (AV-RN-10). */
    @Modifying
    @Query("update PendencyAlertDigest d set d.status = br.com.cshub.taxify.enumerated.PendencyAlertDigestStatus.FAILED"
            + " where d.status = br.com.cshub.taxify.enumerated.PendencyAlertDigestStatus.SENDING and d.referenceDate < :day")
    int failStaleSending(@Param("day") LocalDate day);

    @Modifying
    @Query("update PendencyAlertDigest d set d.status = :status, d.sentAt = :sentAt where d.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") PendencyAlertDigestStatus status,
                     @Param("sentAt") LocalDateTime sentAt);

    /** As tentativas do dia (SENDING, SENT, FAILED) numa leitura travada: enxerga o último commit (teto global, AV-RN-09). */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT COUNT(*) FROM pendency_alert_digests WHERE reference_date = :day"
            + " AND status IN ('SENDING', 'SENT', 'FAILED') LOCK IN SHARE MODE", nativeQuery = true)
    Long countAttemptsLocking(@Param("day") LocalDate day);

    /** Lock nomeado do MySQL (NamedLocks): o teto global entre instâncias. */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT GET_LOCK(:name, :seconds)", nativeQuery = true)
    Long getLock(@Param("name") String name, @Param("seconds") int seconds);

    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT RELEASE_LOCK(:name)", nativeQuery = true)
    Long releaseLock(@Param("name") String name);

    /** Expurgo do cadastro não verificado (F1-RN-03): os resumos dos usuários, apagados fisicamente. */
    @Modifying
    @Query("delete from PendencyAlertDigest d where d.userId in :userIds")
    int deleteByUserIds(@Param("userIds") Collection<UUID> userIds);

    /** Limpeza (AV-RN-14): os resumos de antes de {@code day}. */
    @Modifying
    @Query("delete from PendencyAlertDigest d where d.referenceDate < :day")
    int deleteBefore(@Param("day") LocalDate day);
}
