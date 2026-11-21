package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.user.User;
import org.springframework.stereotype.Repository;

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

    boolean existsByLoginAndDeletedIsFalseAndIdNot(String login, UUID id);
}
