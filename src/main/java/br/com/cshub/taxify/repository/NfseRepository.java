package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.Nfse;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.RetrievalMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The service invoices ({@code from Nfse}, FD-RN-04): an id of another type is "not found" here (FD-RN-17). */
@Repository
public interface NfseRepository extends AbstractJpaRepository<Nfse, UUID>, FiscalDocumentSourceRepository {

    String CANCELED = "br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED";

    Optional<Nfse> findByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    /**
     * The company's NFS-e of a window that a provider itself delivered in the period mode, any direction - compared
     * with SIEG's count before downloading the window (A1). Index idx_fd_company_type_issue.
     */
    @Query("select count(n) from Nfse n where n.company.id = :companyId and n.issueDate between :from and :to"
            + " and exists (select s.id from FiscalDocumentSource s where s.document = n and s.source = :source"
            + "             and s.retrievalMode = :mode)")
    long countDeliveredBy(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                          @Param("source") DocumentSourceKey source, @Param("mode") RetrievalMode mode);

    /**
     * The service notes screen; every filter optional (null = any). {@code role}: the company's role, from the API's
     * PRESTADA/TOMADA/TERCEIRO (LegacyNfseDirection). {@code onlySiegBefore}: seen by SIEG before it and never by the
     * ADN; {@code onlyAdnBefore}: the other way round ({@code fiscal_document_sources}).
     */
    @Query("select n from Nfse n where n.company.id = :companyId and n.deleted = false"
            + " and n.issueDate between :from and :to"
            + " and (:role is null or n.companyRole = :role)"
            + " and (:participant is null or lower(n.issuer.name) like :participant or lower(n.recipient.name) like :participant"
            + "      or n.issuer.document like :participant or n.recipient.document like :participant)"
            + " and (:number is null or n.number = :number)"
            + " and (:onlyRetained = false or n.iss.withheld = true or n.withholdings.irrf > 0 or n.withholdings.csll > 0"
            + "      or n.withholdings.inss > 0 or (n.pisCofinsWithheld = true and (n.pisAmount > 0 or n.cofinsAmount > 0)))"
            + " and (:onlySiegBefore is null"
            + "      or (exists (select s.id from FiscalDocumentSource s where s.document = n and s.source = :sieg"
            + "                  and s.firstSeenAt < :onlySiegBefore)"
            + "      and not exists (select s.id from FiscalDocumentSource s where s.document = n and s.source = :adn)))"
            + " and (:onlyAdnBefore is null"
            + "      or (exists (select s.id from FiscalDocumentSource s where s.document = n and s.source = :adn"
            + "                  and s.firstSeenAt < :onlyAdnBefore)"
            + "      and not exists (select s.id from FiscalDocumentSource s where s.document = n and s.source = :sieg)))")
    Page<Nfse> search(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                      @Param("to") LocalDateTime to, @Param("role") CompanyDocumentRole role,
                      @Param("participant") String participant, @Param("number") String number,
                      @Param("onlyRetained") boolean onlyRetained,
                      @Param("onlySiegBefore") LocalDateTime onlySiegBefore,
                      @Param("onlyAdnBefore") LocalDateTime onlyAdnBefore,
                      @Param("sieg") DocumentSourceKey sieg, @Param("adn") DocumentSourceKey adn, Pageable pageable);

    /** The retentions' source (M1): the companies' non-canceled NFS-e of a competence. */
    @Query("select n from Nfse n join fetch n.company where n.company.id in :companyIds and n.deleted = false"
            + " and n.status <> " + CANCELED + " and n.competence between :from and :to")
    List<Nfse> findForRetentions(@Param("companyIds") Collection<UUID> companyIds,
                                 @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** The period's notes that aren't canceled - totals and retentions. */
    @Query("select n from Nfse n where n.company.id = :companyId and n.deleted = false and n.status <> " + CANCELED
            + " and n.issueDate between :from and :to")
    List<Nfse> findActiveInPeriod(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);
}
