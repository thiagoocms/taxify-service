package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.firmaccess.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain JpaRepository on purpose: the links have no soft delete nor the audit columns (see EmailVerification), so
 * AbstractJpaRepository's {@code ...AndDeletedIsFalse} methods do not apply.
 */
@Repository
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {

    Optional<EmailVerification> findByTokenHash(String tokenHash);

    /** Reenvios de um usuário desde {@code since} - o limite por hora (F1-RN-03). */
    long countByUserIdAndResendIsTrueAndCreatedDateAfter(UUID userId, LocalDateTime since);

    /** Reenvios de um usuário num dia civil [from, to) - o teto diário por login. */
    long countByUserIdAndResendIsTrueAndCreatedDateGreaterThanEqualAndCreatedDateLessThan(UUID userId, LocalDateTime from,
                                                                                          LocalDateTime to);

    /** Reenvios pedidos de um IP num dia civil [from, to) - o teto diário por IP. */
    long countByRequestedIpAndResendIsTrueAndCreatedDateGreaterThanEqualAndCreatedDateLessThan(String requestedIp,
                                                                                               LocalDateTime from, LocalDateTime to);

    /** Marca o link como usado só se ninguém usou antes: 0 = outro pedido chegou primeiro. */
    @Modifying
    @Query("update EmailVerification v set v.usedAt = :now where v.id = :id and v.usedAt is null")
    int markUsed(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** Verificado: o IP dos reenvios não serve mais (o teto diário só conta quem ainda não verificou) e é apagado. */
    @Modifying
    @Query("update EmailVerification v set v.requestedIp = null where v.userId = :userId and v.requestedIp is not null")
    int clearRequestedIp(@Param("userId") UUID userId);

    /** Um reenvio invalida os links anteriores ainda não usados. */
    @Modifying
    @Query("update EmailVerification v set v.usedAt = :now where v.userId = :userId and v.usedAt is null")
    int invalidateOpen(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** Purge of an unverified signup (F1-RN-03): physical delete (LGPD). */
    @Modifying
    @Query("delete from EmailVerification v where v.userId in :userIds")
    int deleteByUserIds(@Param("userIds") Collection<UUID> userIds);
}
