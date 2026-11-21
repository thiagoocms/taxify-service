package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.billing.FirmIntegration;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.enumerated.SyncStatus;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FirmIntegrationRepository extends AbstractJpaRepository<FirmIntegration, UUID> {

    Optional<FirmIntegration> findFirstByAccountingFirm_IdAndTypeAndDeletedIsFalse(UUID accountingFirmId, IntegrationType type);

    List<FirmIntegration> findByAccountingFirm_IdAndDeletedIsFalse(UUID accountingFirmId);

    /** Every active firm's integration of one type - what the schedulers iterate over. */
    List<FirmIntegration> findByTypeAndDeletedIsFalseAndAccountingFirm_DeletedIsFalse(IntegrationType type);

    /**
     * Atomically marks the job RUNNING unless another one is alive - the database row is the
     * lock, so two clicks, the scheduler and the button, or two instances can't overlap.
     * A RUNNING job whose heartbeat is older than {@code staleBefore} is considered dead.
     *
     * @return 1 when this caller got the job, 0 when one is already running
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE FirmIntegration i
               SET i.syncStatus = br.com.cshub.taxify.enumerated.SyncStatus.RUNNING,
                   i.syncStartedAt = :now, i.syncHeartbeatAt = :now, i.syncFinishedAt = null,
                   i.syncProcessed = 0, i.syncTotal = null, i.syncError = null
             WHERE i.id = :id
               AND (i.syncStatus IS NULL
                    OR i.syncStatus <> br.com.cshub.taxify.enumerated.SyncStatus.RUNNING
                    OR i.syncHeartbeatAt IS NULL OR i.syncHeartbeatAt < :staleBefore)
            """)
    int claimSync(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);

    @Modifying
    @Transactional
    @Query("UPDATE FirmIntegration i SET i.syncHeartbeatAt = :now WHERE i.id = :id")
    void touchSync(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Transactional
    @Query("UPDATE FirmIntegration i SET i.syncProcessed = :processed, i.syncTotal = :total, i.syncHeartbeatAt = :now WHERE i.id = :id")
    void updateSyncProgress(@Param("id") UUID id, @Param("processed") Integer processed, @Param("total") Integer total,
                            @Param("now") LocalDateTime now);

    /** {@code result} null keeps the previous one (a failed run doesn't erase the last good summary). */
    @Modifying
    @Transactional
    @Query("""
            UPDATE FirmIntegration i
               SET i.syncStatus = :status, i.syncFinishedAt = :now, i.syncHeartbeatAt = :now,
                   i.syncResult = COALESCE(:result, i.syncResult), i.syncError = :error
             WHERE i.id = :id
            """)
    void completeSync(@Param("id") UUID id, @Param("status") SyncStatus status, @Param("result") String result,
                      @Param("error") String error, @Param("now") LocalDateTime now);
}
