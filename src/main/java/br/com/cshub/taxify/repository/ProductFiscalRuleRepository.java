package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductFiscalRuleRepository extends AbstractJpaRepository<ProductFiscalRule, UUID> {

    Optional<ProductFiscalRule> findFirstByCompany_IdAndCodigoProdutoAndDeletedIsFalse(UUID companyId, String codigoProduto);

    boolean existsByCompany_IdAndCodigoProdutoAndDeletedIsFalse(UUID companyId, String codigoProduto);

    boolean existsByCompany_IdAndCodigoProdutoAndDeletedIsFalseAndIdNot(UUID companyId, String codigoProduto, UUID id);

    @Query("select max(r.lastModifiedDate) from ProductFiscalRule r where r.company.id = :companyId")
    Optional<LocalDateTime> findMaxLastModifiedDateByCompanyId(UUID companyId);

    @Query("select r.codigoProduto from ProductFiscalRule r "
            + "where r.company.id = :companyId and r.deleted = false and r.codigoProduto in :codigos")
    List<String> findCodigosWithRule(@Param("companyId") UUID companyId, @Param("codigos") Collection<String> codigos);
}
