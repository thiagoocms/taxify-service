package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.user.User;
import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserRepository extends AbstractJpaRepository<User, UUID> {
    User findByLoginAndDeletedIsFalse(String login);

    List<User> findByAccountingFirm_IdAndDeletedIsFalse(UUID accountingFirmId);

    long countByAccountingFirm_IdAndProfileAndDeletedIsFalse(UUID accountingFirmId, UserProfileEnum profile);

    /*
     * Login in use by another active user. Derived queries, not native SQL: a UUID bound to a
     * native query goes as 16 binary bytes, and MySQL can't compare that with the varchar id.
     */
    boolean existsByLoginAndDeletedIsFalse(String login);

    /** Login usado por qualquer usuário, excluído ou não - uk_users_login vale para todos (cadastro público). */
    boolean existsByLogin(String login);

    boolean existsByLoginAndDeletedIsFalseAndIdNot(String login, UUID id);

    /** Login de qualquer outro usuário, excluído ou não - uk_users_login vale para todos (edição de usuário). */
    boolean existsByLoginAndIdNot(String login, UUID id);

    /** Usuários em uso do escritório (não excluídos, de qualquer tipo) - o uso do limite do teste na tela (F1-RN-10a). */
    long countByAccountingFirm_IdAndDeletedIsFalse(UUID accountingFirmId);

    /**
     * The same count as a locking read (LOCK IN SHARE MODE): it sees what another transaction has just written - the
     * trial limit check, after the firm's row lock (F1-RN-10a). Native: JPQL has no shared lock MySQL 5.7 accepts; the
     * id goes as text; no flush first (the user being created is not in the database yet).
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT COUNT(*) FROM users WHERE accounting_firm_id = :firmId AND deleted = 0 LOCK IN SHARE MODE",
            nativeQuery = true)
    Long countInUseLocking(@Param("firmId") String accountingFirmId);

    /** Console (F1-RN-15): usuários em uso por escritório - [firm id, count]. */
    @Query("select u.accountingFirm.id, count(u) from User u where u.deleted = false and u.accountingFirm.id in :firmIds"
            + " group by u.accountingFirm.id")
    List<Object[]> countInUseByFirm(@Param("firmIds") Collection<UUID> firmIds);

    /** Console (F1-RN-15): nome e e-mail dos usuários de um tipo (os ADMIN), por nome - [firm id, name, email]. */
    @Query("select u.accountingFirm.id, u.name, u.email from User u where u.deleted = false and u.profile = :profile"
            + " and u.accountingFirm.id in :firmIds order by u.name")
    List<Object[]> findContactsByFirm(@Param("firmIds") Collection<UUID> firmIds, @Param("profile") UserProfileEnum profile);

    /** Purge of an unverified signup (F1-RN-03): every user of the firm, deleted or not - [user id, document id]. */
    @Query("select u.id, d.id from User u left join u.document d where u.accountingFirm.id = :firmId")
    List<Object[]> findIdsAndDocumentIdsByFirm(@Param("firmId") UUID accountingFirmId);

    /**
     * Purge (F1-RN-03): the access profiles of the users (user_access_profiles, an @ElementCollection - no entity to
     * delete through JPQL, hence native; the ids as text). Physical delete (LGPD).
     */
    @Modifying
    @Query(value = "DELETE FROM user_access_profiles WHERE user_id IN (:userIds)", nativeQuery = true)
    int deleteAccessProfilesOf(@Param("userIds") Collection<String> userIds);

    /** Purge (F1-RN-03): physical delete (LGPD). */
    @Modifying
    @Query("delete from User u where u.accountingFirm.id = :firmId")
    int deleteByFirm(@Param("firmId") UUID accountingFirmId);
}
