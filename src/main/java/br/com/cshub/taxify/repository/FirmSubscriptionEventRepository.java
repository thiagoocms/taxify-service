package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.firmaccess.FirmSubscriptionEvent;
import br.com.cshub.taxify.enumerated.FirmEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JpaRepository on purpose: the trail is append-only, without soft delete or the audit columns (see
 * FirmSubscriptionEvent), so AbstractJpaRepository's {@code ...AndDeletedIsFalse} methods do not apply.
 */
@Repository
public interface FirmSubscriptionEventRepository extends JpaRepository<FirmSubscriptionEvent, UUID> {

    List<FirmSubscriptionEvent> findByAccountingFirmIdOrderByCreatedDateDesc(UUID accountingFirmId);

    Optional<FirmSubscriptionEvent> findFirstByAccountingFirmIdAndTypeOrderByCreatedDateDesc(UUID accountingFirmId, FirmEventType type);

    /**
     * Purge of an unverified signup (F1-RN-03): the only events that carry personal data are the contact ones - they
     * go with the signup. Physical delete (LGPD).
     */
    @Modifying
    @Query("delete from FirmSubscriptionEvent e where e.accountingFirmId = :firmId and e.type = :type")
    int deleteByFirmAndType(@Param("firmId") UUID accountingFirmId, @Param("type") FirmEventType type);
}
