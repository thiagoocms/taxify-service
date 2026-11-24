package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fronteira.FronteiraRule;
import br.com.cshub.taxify.enumerated.FronteiraRuleKind;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FronteiraRuleRepository extends AbstractJpaRepository<FronteiraRule, UUID> {

    /** The global rules and the company's own, not deleted (enabled or not - the screen lists both). */
    @Query("select r from FronteiraRule r left join r.company c where r.deleted = false and (c is null or c.id = :companyId)"
            + " order by r.kind, r.ncm, r.createdDate")
    List<FronteiraRule> findForCompany(@Param("companyId") UUID companyId);

    @Query("select r from FronteiraRule r where r.deleted = false and r.company is null order by r.kind, r.ncm, r.createdDate")
    List<FronteiraRule> findGlobal();

    /** The rules of a kind, global or the company's - deleted ones too (someone removed it on purpose). */
    @Query("select r from FronteiraRule r left join r.company c where r.kind = :kind and (c is null or c.id = :companyId)")
    List<FronteiraRule> findAnyOfKind(@Param("kind") FronteiraRuleKind kind, @Param("companyId") UUID companyId);

    /** Whether the modal internal rate was ever created (deleted counts: then someone removed it on purpose). */
    boolean existsByKindAndCompanyIsNullAndNcmIsNull(FronteiraRuleKind kind);
}
