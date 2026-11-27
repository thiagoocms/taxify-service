package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.notification.UserNotificationSetting;
import br.com.cshub.taxify.enumerated.NotificationType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserNotificationSettingRepository extends AbstractJpaRepository<UserNotificationSetting, UUID> {

    Optional<UserNotificationSetting> findByUserIdAndTypeAndDeletedIsFalse(UUID userId, NotificationType type);

    /** Expurgo do cadastro não verificado (F1-RN-03). */
    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from UserNotificationSetting s where s.userId in :userIds")
    int deleteByUserIds(@Param("userIds") Collection<UUID> userIds);

    /** As preferências gravadas de vários usuários de uma vez (o resumo diário, sem N+1). */
    @Query("select s from UserNotificationSetting s where s.deleted = false and s.type = :type and s.userId in :userIds")
    List<UserNotificationSetting> findByType(@Param("type") NotificationType type, @Param("userIds") Collection<UUID> userIds);
}
