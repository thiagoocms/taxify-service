package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * The years of IBS/CBS reference rates. The id is the year ({@code ano}): the CRUD base's {@code findFirstById*} are
 * declared here by query, since the entity has no attribute named {@code id} (it is {@code year}).
 */
@Repository
public interface IbsCbsRateRepository extends AbstractJpaRepository<IbsCbsRate, Integer> {

    @Override
    @Query("select r from IbsCbsRate r where r.year = :id")
    Optional<IbsCbsRate> findFirstById(@Param("id") Integer id);

    @Override
    @Query("select r from IbsCbsRate r where r.year = :id and r.deleted = false")
    Optional<IbsCbsRate> findFirstByIdAndDeletedIsFalse(@Param("id") Integer id);

    /**
     * The year locked until the end of the transaction (AL-RN-04: two admins saving the same year wait in line). Plain
     * {@code FOR UPDATE}: valid on MySQL 5.7 and 8.4 (the dialect's {@code FOR UPDATE OF} is 8.0+ only).
     */
    @Query(value = "SELECT * FROM ibs_cbs_rates WHERE ano = :year AND deleted = 0 FOR UPDATE", nativeQuery = true)
    Optional<IbsCbsRate> findForUpdate(@Param("year") Integer year);

    /** Every year, ascending - console and firms' reading (AL-RN-06). */
    @Query("select r from IbsCbsRate r where r.deleted = false order by r.year")
    List<IbsCbsRate> findAllYears();

    /** AL-RN-02: only LAW with the three rates - one query, what the conferência's context loads. */
    @Query("select r from IbsCbsRate r where r.deleted = false"
            + " and r.status = br.com.cshub.taxify.enumerated.IbsCbsRateStatus.LAW"
            + " and r.cbsRate is not null and r.ibsStateRate is not null and r.ibsMunicipalRate is not null")
    List<IbsCbsRate> findFindingRates();
}
