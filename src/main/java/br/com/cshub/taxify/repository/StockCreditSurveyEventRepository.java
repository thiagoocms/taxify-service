package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.reform.stock.StockCreditSurveyEvent;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface StockCreditSurveyEventRepository extends AbstractJpaRepository<StockCreditSurveyEvent, UUID> {

    /** Most recent first; a handful per company, no paging. */
    @Query("select e from StockCreditSurveyEvent e where e.surveyId = :surveyId and e.deleted = false"
            + " order by e.changedAt desc, e.createdDate desc")
    List<StockCreditSurveyEvent> history(@Param("surveyId") UUID surveyId);
}
