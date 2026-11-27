package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.firmaccess.FirmSubscription;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FirmSubscriptionRepository extends AbstractJpaRepository<FirmSubscription, UUID> {

    Optional<FirmSubscription> findByAccountingFirmIdAndDeletedIsFalse(UUID accountingFirmId);

    /**
     * The firm's row read with SELECT ... FOR UPDATE: the latest committed state, locked until the end of the
     * transaction - the e-mail verification, the purge, the daily BLOCKED record and the platform admin's actions all
     * read it this way, so none of them overwrites another. Native on purpose: Hibernate's PESSIMISTIC_WRITE writes
     * "FOR UPDATE OF alias", which MySQL 5.7 does not accept; the id goes as text (a bound UUID would be binary).
     */
    @Query(value = "SELECT * FROM firm_subscriptions WHERE accounting_firm_id = :firmId AND deleted = 0 FOR UPDATE",
            nativeQuery = true)
    Optional<FirmSubscription> findForUpdate(@Param("firmId") String accountingFirmId);

    default Optional<FirmSubscription> findForUpdate(UUID accountingFirmId) {
        return findForUpdate(accountingFirmId.toString());
    }

    /**
     * Only the status, locked (SELECT ... FOR UPDATE, native for the same reason as {@link #findForUpdate(String)}):
     * the purge of an unverified signup (F1-RN-03) locks the same row as the verification, and whoever comes second
     * sees the other's result. A scalar on purpose: always read from the database, never from the persistence context.
     */
    @Query(value = "SELECT status FROM firm_subscriptions WHERE accounting_firm_id = :firmId FOR UPDATE", nativeQuery = true)
    List<String> findStatusForUpdate(@Param("firmId") String accountingFirmId);

    /**
     * The columns of the access situation (status, trial_ends_on, access_until, blocked_since, blocked_reason), read with
     * SELECT ... FOR UPDATE for the trial limit check (F1-RN-10a): a scalar read on purpose - the subscription entity may
     * already be in the persistence context of the same transaction, and a native query returning the entity would hand
     * that (older) instance back. No flush first: the company or user being written is not in the database yet.
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT status, trial_ends_on, access_until, blocked_since, blocked_reason FROM firm_subscriptions"
            + " WHERE accounting_firm_id = :firmId AND deleted = 0 FOR UPDATE", nativeQuery = true)
    List<Object[]> findAccessForUpdate(@Param("firmId") String accountingFirmId);

    List<FirmSubscription> findByAccountingFirmIdInAndDeletedIsFalse(Collection<UUID> accountingFirmIds);

    List<FirmSubscription> findByStatusInAndDeletedIsFalse(Collection<FirmSubscriptionStatus> statuses);

    /** Physical delete, only by the purge of an unverified signup (LGPD, F1-RN-03) - everything else is soft delete. */
    @Modifying
    @Query("delete from FirmSubscription s where s.accountingFirmId = :firmId")
    int deleteByFirm(@Param("firmId") UUID accountingFirmId);
}
