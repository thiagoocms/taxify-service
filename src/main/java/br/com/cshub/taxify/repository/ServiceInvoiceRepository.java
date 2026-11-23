package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.xml.ServiceInvoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceInvoiceRepository extends AbstractJpaRepository<ServiceInvoice, UUID> {

    Optional<ServiceInvoice> findByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    /** The service notes screen; every filter optional (null = any). */
    @Query("select s from ServiceInvoice s where s.company.id = :companyId and s.deleted = false"
            + " and s.issueDate between :from and :to"
            + " and (:direction is null or s.direction = :direction)"
            + " and (:participant is null or lower(s.prestadorNome) like :participant or lower(s.tomadorNome) like :participant"
            + "      or s.prestadorDocumento like :participant or s.tomadorDocumento like :participant)"
            + " and (:numero is null or s.numero = :numero)"
            + " and (:onlyRetained = false or s.issRetido = true or s.valorIrrf > 0 or s.valorCsll > 0 or s.valorInss > 0"
            + "      or (s.pisCofinsRetidos = true and (s.valorPis > 0 or s.valorCofins > 0)))"
            + " and (:onlySiegBefore is null or (s.foundInSiegAt < :onlySiegBefore and s.foundInAdnAt is null))"
            + " and (:onlyAdnBefore is null or (s.foundInAdnAt < :onlyAdnBefore and s.foundInSiegAt is null))")
    Page<ServiceInvoice> search(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to, @Param("direction") String direction,
                                @Param("participant") String participant, @Param("numero") String numero,
                                @Param("onlyRetained") boolean onlyRetained,
                                @Param("onlySiegBefore") LocalDateTime onlySiegBefore,
                                @Param("onlyAdnBefore") LocalDateTime onlyAdnBefore, Pageable pageable);

    /** The retentions' source (M1): the companies' non-canceled NFS-e of a competence. */
    @Query("select s from ServiceInvoice s join fetch s.company where s.company.id in :companyIds and s.deleted = false"
            + " and s.canceled = false and s.competence between :from and :to")
    List<ServiceInvoice> findForRetentions(@Param("companyIds") java.util.Collection<UUID> companyIds,
                                           @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);

    /** The period's notes that aren't canceled - totals and retentions. */
    @Query("select s from ServiceInvoice s where s.company.id = :companyId and s.deleted = false and s.canceled = false"
            + " and s.issueDate between :from and :to")
    List<ServiceInvoice> findActiveInPeriod(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                                            @Param("to") LocalDateTime to);
}
