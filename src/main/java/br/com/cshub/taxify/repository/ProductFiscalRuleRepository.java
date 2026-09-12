package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductFiscalRuleRepository extends AbstractJpaRepository<ProductFiscalRule, UUID> {

    Optional<ProductFiscalRule> findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(UUID companyId, String codigoProduto);

    boolean existsByCompany_IdAndCodigoProdutoAndDeletedIsFalse(UUID companyId, String codigoProduto);

    boolean existsByCompany_IdAndCodigoProdutoAndDeletedIsFalseAndIdNot(UUID companyId, String codigoProduto, UUID id);

    @Query("select max(r.lastModifiedDate) from ProductFiscalRule r where r.company.id = :companyId")
    Optional<LocalDateTime> findMaxLastModifiedDateByCompanyId(UUID companyId);
}
