package br.com.cshub.taxify.repository;

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

    /** Active (not deleted) companies of a firm, by name - the firm-wide assessment rows. */
    List<Company> findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(UUID accountingFirmId);

}
