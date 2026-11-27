package br.com.cshub.taxify.repository;

import jakarta.persistence.QueryHint;
import org.hibernate.jpa.HibernateHints;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.company.Company;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface CompanyRepository extends AbstractJpaRepository<Company, UUID> {

    /** Soft-deleted ones included - the Acessórias import must not re-create a company the firm deleted. */
    List<Company> findByAccountingFirm_Id(UUID accountingFirmId);

    /** Companies whose regime is only a label - structured by CompanyTaxRegimeBackfill. */
    List<Company> findByTaxRegimeIsNotNullAndTaxRegimeTypeIsNull();

    /** Companies with a regime but no regime history yet - get a "since always" period on startup. */
    @Query("select c from Company c where c.taxRegimeType is not null and not exists"
            + " (select 1 from CompanyTaxRegime r where r.company = c and r.deleted = false)")
    List<Company> findWithRegimeAndNoHistory();

    /** Active (not deleted) companies of a firm, by name - the firm-wide assessment rows. The CNPJ comes in the same query. */
    @EntityGraph(attributePaths = "document")
    List<Company> findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(UUID accountingFirmId);

    /** Pendency digest (AV-RN-01): the companies of a firm that count, as in the panel - not deleted, not inactive. */
    @Query("select c.id from Company c where c.accountingFirm.id = :firmId and c.deleted = false"
            + " and (c.active is null or c.active = true)")
    List<UUID> findActiveIdsByFirm(@Param("firmId") UUID accountingFirmId);


    /**
     * Empresas em uso do escritório - não excluídas e {@code active} nulo ou true (F1-RN-10a); cada CNPJ completo conta
     * (matriz e filiais à parte). O uso do limite do teste na tela.
     */
    @Query("select count(c) from Company c where c.accountingFirm.id = :firmId and c.deleted = false"
            + " and (c.active is null or c.active = true)")
    long countInUseByFirm(@Param("firmId") UUID accountingFirmId);

    /**
     * The same count as a locking read (LOCK IN SHARE MODE): it sees what another transaction has just written - the
     * trial limit check, after the firm's row lock (F1-RN-10a). Native: JPQL has no shared lock MySQL 5.7 accepts; the
     * id goes as text; no flush first (a company being created or reactivated is not in the database yet).
     */
    @QueryHints(@QueryHint(name = HibernateHints.HINT_FLUSH_MODE, value = "COMMIT"))
    @Query(value = "SELECT COUNT(*) FROM companies WHERE accounting_firm_id = :firmId AND deleted = 0"
            + " AND (active IS NULL OR active = 1) LOCK IN SHARE MODE", nativeQuery = true)
    Long countInUseLocking(@Param("firmId") String accountingFirmId);

    /** Console (F1-RN-15): empresas em uso e inativas por escritório - [firm id, in use, inactive]; nunca nome ou CNPJ. */
    @Query("select c.accountingFirm.id, sum(case when c.active is null or c.active = true then 1 else 0 end),"
            + " sum(case when c.active = false then 1 else 0 end) from Company c"
            + " where c.deleted = false and c.accountingFirm.id in :firmIds group by c.accountingFirm.id")
    List<Object[]> countInUseAndInactiveByFirm(@Param("firmIds") Collection<UUID> firmIds);

    /** Every company of a UF, all firms - the weekly certidão SEFAZ-PE job goes through them. */
    @Query("select c from Company c join fetch c.document where c.deleted = false and upper(trim(c.uf)) = upper(:uf)")
    List<Company> findAllOfUf(@org.springframework.data.repository.query.Param("uf") String uf);
}
