package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.domain.user_company.UserCompanyId;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserCompanyRepository extends AbstractJpaRepository<UserCompany, UserCompanyId> {

    boolean existsByUser_IdAndCompany_IdAndDeletedIsFalse(UUID userId, UUID companyId);
}
