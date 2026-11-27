package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.password.PasswordResetToken;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** Links de redefinição de senha (SN-RN-03 a SN-RN-06, SN-RN-15). Horários de Recife em {@code requested_at}. */
@Repository
public interface PasswordResetTokenRepository extends AbstractJpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHashAndDeletedIsFalse(String tokenHash);

    /** Links de uma conta desde {@code since} - o limite por hora (SN-RN-04). */
    long countByUserIdAndRequestedAtGreaterThanEqual(UUID userId, LocalDateTime since);

    /** Links de uma conta num dia civil [from, to) - o teto diário por conta. */
    long countByUserIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(UUID userId, LocalDateTime from, LocalDateTime to);

    /**
     * Links pedidos de um IP num dia civil [from, to), somando as contas - o teto diário por IP. Leitura travada (LOCK IN
     * SHARE MODE): enxerga o último commit, e não o retrato da transação - junto com o lock por IP (NamedLocks), duas
     * instâncias nunca passam do teto. Nativo; sem flush antes.
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT COUNT(*) FROM password_reset_tokens WHERE requested_ip = :ip AND requested_at >= :from"
            + " AND requested_at < :to LOCK IN SHARE MODE", nativeQuery = true)
    Long countByIpLocking(@Param("ip") String requestedIp, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Marca o link como usado só se ele ainda vale: 0 = outro pedido chegou primeiro, ou ele foi invalidado ou venceu
     * (dois cliques ao mesmo tempo: só um passa - SN-RN-06).
     */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.id = :id and t.usedAt is null"
            + " and t.invalidatedAt is null and t.deleted = false and t.expiresAt >= :now")
    int markUsed(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** Invalida os links abertos da conta (pedido novo, redefinição ou troca de senha). */
    @Modifying
    @Query("update PasswordResetToken t set t.invalidatedAt = :now where t.userId = :userId and t.usedAt is null"
            + " and t.invalidatedAt is null")
    int invalidateOpen(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** Senha redefinida: o IP dos pedidos só servia para o limite e é apagado (LGPD). */
    @Modifying
    @Query("update PasswordResetToken t set t.requestedIp = null where t.userId = :userId and t.requestedIp is not null")
    int clearRequestedIp(@Param("userId") UUID userId);

    /** Lock nomeado do MySQL (NamedLocks): 1 pegou, 0 tempo esgotado. Nativo e sem flush; roda na conexão da transação. */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT GET_LOCK(:name, :seconds)", nativeQuery = true)
    Long getLock(@Param("name") String name, @Param("seconds") int seconds);

    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT RELEASE_LOCK(:name)", nativeQuery = true)
    Long releaseLock(@Param("name") String name);

    /** Expurgo do cadastro não verificado (F1-RN-03): apaga fisicamente os links dos usuários. */
    @Modifying
    @Query("delete from PasswordResetToken t where t.userId in :userIds")
    int deleteByUserIds(@Param("userIds") java.util.Collection<UUID> userIds);

    /** Limpeza (SN-RN-15): apaga fisicamente os links pedidos antes de {@code before}. */
    @Modifying
    @Transactional
    @Query("delete from PasswordResetToken t where t.requestedAt < :before")
    int deleteRequestedBefore(@Param("before") LocalDateTime before);
}
