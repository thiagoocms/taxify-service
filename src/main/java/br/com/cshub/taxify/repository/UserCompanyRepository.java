package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.domain.user_company.UserCompanyId;
import org.springframework.stereotype.Repository;

@Repository
public interface UserCompanyRepository extends AbstractJpaRepository<UserCompany, UserCompanyId> {

}
