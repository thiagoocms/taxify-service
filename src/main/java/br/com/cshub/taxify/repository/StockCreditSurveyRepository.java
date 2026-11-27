package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.reform.stock.StockCreditSurvey;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface StockCreditSurveyRepository extends AbstractJpaRepository<StockCreditSurvey, UUID> {

    @Query("select s from StockCreditSurvey s where s.companyId = :companyId and s.surveyYear = :year and s.deleted = false")
    Optional<StockCreditSurvey> findOf(@Param("companyId") UUID companyId, @Param("year") Integer year);

    /** The row locked until the end of the transaction (CE-RN-10: two status changes wait in line); 5.7 and 8.4. */
    @Query(value = "SELECT * FROM stock_credit_surveys WHERE company_id = :companyId AND survey_year = :year AND deleted = 0"
            + " FOR UPDATE", nativeQuery = true)
    Optional<StockCreditSurvey> findForUpdate(@Param("companyId") String companyId, @Param("year") Integer year);
}
