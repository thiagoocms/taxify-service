package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.user.User;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserRepository extends AbstractJpaRepository<User, UUID> {
    User findByLoginAndDeletedIsFalse(String login);

    @NativeQuery(
        value = "SELECT COUNT(*) FROM users WHERE (:id IS NULL OR id <> :id) AND login = :login AND deleted = false"
    )
    Long countByLoginAndDeletedIsFalse(@Param("id") UUID id, @Param("login") String login);
}
