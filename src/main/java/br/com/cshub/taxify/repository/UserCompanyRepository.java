package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.domain.user_company.UserCompanyId;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Repository
public interface UserCompanyRepository extends AbstractJpaRepository<UserCompany, UserCompanyId> {

    boolean existsByUser_IdAndCompany_IdAndDeletedIsFalse(UUID userId, UUID companyId);

    /** The companies of a (non-admin) user's portfolio, in one query - instead of one exists per company. */
    @Query("select uc.company.id from UserCompany uc where uc.user.id = :userId and uc.deleted = false")
    Set<UUID> findCompanyIdsByUser(@Param("userId") UUID userId);

    /** The portfolios of several users in one query - [user id, company id] (pendency digest, AV-RN-02). */
    @Query("select uc.user.id, uc.company.id from UserCompany uc where uc.deleted = false and uc.user.id in :userIds")
    java.util.List<Object[]> findPortfolios(@Param("userIds") Collection<UUID> userIds);

    /** Purge of an unverified signup (F1-RN-03): the portfolio links of its users. Physical delete (LGPD). */
    @Modifying
    @Query("delete from UserCompany uc where uc.id.userId in :userIds")
    int deleteByUserIds(@Param("userIds") Collection<UUID> userIds);
}
