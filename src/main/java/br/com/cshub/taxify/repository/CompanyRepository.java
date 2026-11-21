package br.com.cshub.taxify.repository;

import org.springframework.data.jpa.repository.Query;
import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.company.Company;
import org.springframework.stereotype.Repository;

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

    /** Active (not deleted) companies of a firm, by name - the firm-wide assessment rows. */
    List<Company> findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(UUID accountingFirmId);

}
