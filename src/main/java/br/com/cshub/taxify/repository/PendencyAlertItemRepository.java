package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.pendency.PendencyAlertItem;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Pendências já avisadas por episódio (AV-RN-04, AV-RN-14). */
@Repository
public interface PendencyAlertItemRepository extends AbstractJpaRepository<PendencyAlertItem, UUID> {

    /** Limpeza (AV-RN-14): itens de pendência fechada antes de {@code closedBefore} (se ela reabrir, o episódio é outro). */
    @Modifying
    @Query("delete from PendencyAlertItem i where i.pendencyId in (select p.id from Pendency p"
            + " where p.status <> br.com.cshub.taxify.enumerated.PendencyStatus.ABERTA and p.closedAt < :closedBefore)")
    int deleteOfPendenciesClosedBefore(@Param("closedBefore") LocalDateTime closedBefore);

    /** Expurgo do cadastro não verificado (F1-RN-03): os itens avisados aos usuários. */
    @Modifying
    @Query("delete from PendencyAlertItem i where i.userId in :userIds")
    int deleteByUserIds(@Param("userIds") java.util.Collection<UUID> userIds);

    /** Antes de apagar os resumos antigos: o item continua (é a deduplicação), só perde o vínculo. */
    @Modifying
    @Query("update PendencyAlertItem i set i.digestId = null where i.digestId in"
            + " (select d.id from PendencyAlertDigest d where d.referenceDate < :day)")
    int detachFromDigestsBefore(@Param("day") LocalDate day);
}
