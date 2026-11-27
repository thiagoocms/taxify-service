package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.reform.IbsCbsRateChange;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IbsCbsRateChangeRepository extends AbstractJpaRepository<IbsCbsRateChange, UUID> {

    /** The year's history, most recent first (AL-RN-04). */
    @Query("select c from IbsCbsRateChange c where c.rateYear = :year and c.deleted = false"
            + " order by c.changedAt desc, c.createdDate desc")
    List<IbsCbsRateChange> history(@Param("year") Integer year);
}
