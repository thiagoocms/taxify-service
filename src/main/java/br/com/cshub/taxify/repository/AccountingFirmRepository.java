package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AccountingFirmRepository extends AbstractJpaRepository<AccountingFirm, UUID> {

    List<AccountingFirm> findByAcessoriasApiTokenIsNotNullAndDeletedIsFalse();

}
