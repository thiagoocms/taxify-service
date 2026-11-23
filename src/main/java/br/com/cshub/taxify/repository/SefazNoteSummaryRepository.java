package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary.CienciaStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SefazNoteSummaryRepository extends AbstractJpaRepository<SefazNoteSummary, UUID> {

    Optional<SefazNoteSummary> findByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    /** Still waiting for the full XML, oldest first - the screen's list. */
    List<SefazNoteSummary> findByCompany_IdAndCompletedAtIsNullOrderByIssueDateAsc(UUID companyId, Pageable pageable);

    /** Authorized summaries a ciência should be sent for: never sent, or refused fewer than {@code maxAttempts} times. */
    @Query("select s from SefazNoteSummary s where s.company.id = :companyId and s.completedAt is null"
            + " and (s.situation is null or s.situation = '1')"
            + " and (s.cienciaStatus = br.com.cshub.taxify.domain.xml.SefazNoteSummary.CienciaStatus.PENDENTE"
            + "      or (s.cienciaStatus = br.com.cshub.taxify.domain.xml.SefazNoteSummary.CienciaStatus.ERRO"
            + "          and s.cienciaAttempts < :maxAttempts))"
            + " order by s.issueDate asc")
    List<SefazNoteSummary> findAwaitingCiencia(@Param("companyId") UUID companyId, @Param("maxAttempts") int maxAttempts,
                                               Pageable pageable);

    /** Ciência registered before {@code sentBefore}, full XML not here yet and not asked since {@code fetchedBefore}. */
    @Query("select s from SefazNoteSummary s where s.company.id = :companyId and s.completedAt is null"
            + " and s.cienciaStatus = br.com.cshub.taxify.domain.xml.SefazNoteSummary.CienciaStatus.ENVIADA"
            + " and s.cienciaAt < :sentBefore"
            + " and (s.lastFetchAt is null or s.lastFetchAt < :fetchedBefore)"
            + " order by s.cienciaAt asc")
    List<SefazNoteSummary> findAwaitingFullXml(@Param("companyId") UUID companyId, @Param("sentBefore") LocalDateTime sentBefore,
                                               @Param("fetchedBefore") LocalDateTime fetchedBefore, Pageable pageable);

    long countByCompany_IdAndCompletedAtIsNullAndCienciaStatus(UUID companyId, CienciaStatus status);
}
