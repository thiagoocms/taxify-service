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

    List<ProductFiscalRule> findByCompany_IdAndDeletedIsFalse(UUID companyId);

    /** Another active rule for the same code and supplier (null = the general rule); {@code id} null on create. */
    @Query("select count(r) > 0 from ProductFiscalRule r where r.company.id = :companyId and r.deleted = false"
            + " and r.codigoProduto = :codigo"
            + " and ((:supplier is null and r.documentoFornecedor is null) or r.documentoFornecedor = :supplier)"
            + " and (:id is null or r.id <> :id)")
    boolean existsDuplicate(@Param("companyId") UUID companyId, @Param("codigo") String codigoProduto,
                            @Param("supplier") String documentoFornecedor, @Param("id") UUID id);

    /** Companies (not deleted) with at least one active rule - the ones the scheduled rectification has work for. */
    @Query("select distinct r.company from ProductFiscalRule r where r.deleted = false and r.company.deleted = false")
    List<br.com.cshub.taxify.domain.company.Company> findCompaniesWithRules();

    @Query("select max(r.lastModifiedDate) from ProductFiscalRule r where r.company.id = :companyId")
    Optional<LocalDateTime> findMaxLastModifiedDateByCompanyId(UUID companyId);

    @Query("select r.codigoProduto from ProductFiscalRule r "
            + "where r.company.id = :companyId and r.deleted = false and r.codigoProduto in :codigos")
    List<String> findCodigosWithRule(@Param("companyId") UUID companyId, @Param("codigos") Collection<String> codigos);
}
