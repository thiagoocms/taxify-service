package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.enumerated.ReviewStatus;
import java.util.Collection;
import java.util.Optional;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface FiscalXmlDocumentRepository extends AbstractJpaRepository<FiscalXmlDocument, UUID> {

    String CANCELED = "br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED";

    boolean existsByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    Optional<FiscalXmlDocument> findFirstByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    long countByCompany_Id(UUID companyId);

    long countByCompany_IdAndIssueDateBetween(UUID companyId, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalXmlDocument> findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(
            UUID companyId, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalXmlDocument> findByIssueDateBeforeAndDeletedIsFalse(LocalDateTime cutoff);

    /** What a sync side already holds in a window - compared with the provider's count for that side. */
    long countByCompany_IdAndOwnIssueAndIssueDateBetween(UUID companyId, Boolean ownIssue,
                                                         LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    /** Rows imported before the direction column existed - FiscalDirectionBackfill fills them. */
    List<FiscalXmlDocument> findTop500ByDirectionIsNull();

    /**
     * The notes screen. Every filter is optional (null = not applied): {@code numero} exact,
     * {@code direction} entry/exit, {@code participant} a lower-case piece of the issuer's or
     * recipient's name, or digits of their CNPJ/CPF.
     */
    @Query("select d from FiscalXmlDocument d where d.company.id = :companyId and d.deleted = false"
            + " and d.issueDate between :issueDateFrom and :issueDateTo"
            + " and (:numero is null or d.numero = :numero)"
            + " and (:direction is null or d.direction = :direction)"
            + " and (:participant is null"
            + "      or lower(d.emitenteNome) like :participant or lower(d.destinatarioNome) like :participant"
            + "      or d.issuerCnpj like :participant or d.recipientCnpj like :participant"
            + "      or d.emitenteCpf like :participant or d.destinatarioCpf like :participant)"
            + " and (:reviewStatus is null"
            + "      or coalesce(d.reviewStatus, br.com.cshub.taxify.enumerated.ReviewStatus.PENDING) = :reviewStatus)"
            + " and (:origem is null or d.sourceSystem = :origem)"
            + " and (:onlySiegBefore is null or (d.foundInSiegAt < :onlySiegBefore and d.foundInSefazAt is null"
            + "      and d.ownIssue = false))"
            + " and (:onlySefazBefore is null or (d.foundInSefazAt < :onlySefazBefore and d.foundInSiegAt is null))")
    Page<FiscalXmlDocument> search(@Param("companyId") UUID companyId,
                                   @Param("issueDateFrom") LocalDateTime issueDateFrom,
                                   @Param("issueDateTo") LocalDateTime issueDateTo,
                                   @Param("numero") String numero,
                                   @Param("direction") FiscalDirection direction,
                                   @Param("participant") String participant,
                                   @Param("reviewStatus") ReviewStatus reviewStatus,
                                   @Param("origem") String origem,
                                   @Param("onlySiegBefore") LocalDateTime onlySiegBefore,
                                   @Param("onlySefazBefore") LocalDateTime onlySefazBefore,
                                   Pageable pageable);

    /**
     * Entries the company still owes a conclusive manifestation for: NF-e (model 55) addressed to its
     * CNPJ, not canceled, issued since {@code since}, without a registered confirmation, unknown or
     * not-performed event.
     */
    @Query("select d from FiscalXmlDocument d where d.company.id = :companyId and d.deleted = false"
            + " and d.ownIssue = false and d.recipientCnpj = :cnpj and d.documentType = 'NFE'"
            + " and (d.modelo is null or d.modelo = '55')"
            + " and (d.status is null or d.status <> br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED)"
            + " and d.issueDate >= :since and d.accessKey is not null"
            + " and not exists (select e.id from FiscalXmlEvent e where e.company.id = :companyId"
            + "      and e.accessKey = d.accessKey and e.registered = true and e.eventType in :conclusive)"
            + " order by d.issueDate asc")
    List<FiscalXmlDocument> findAwaitingManifestation(@Param("companyId") UUID companyId, @Param("cnpj") String cnpj,
                                                      @Param("since") LocalDateTime since,
                                                      @Param("conclusive") java.util.Collection<String> conclusive,
                                                      Pageable pageable);

    /** SIEG x SEFAZ: third-party notes SIEG brought before {@code before}, issued since {@code issuedSince}, never seen by SEFAZ. */
    @Query("select count(d) from FiscalXmlDocument d where d.company.id = :companyId and d.deleted = false"
            + " and d.ownIssue = false and d.foundInSiegAt < :before and d.foundInSefazAt is null"
            + " and d.issueDate >= :issuedSince")
    long countOnlySieg(@Param("companyId") UUID companyId, @Param("before") LocalDateTime before,
                       @Param("issuedSince") LocalDateTime issuedSince);

    /**
     * The retentions' source (M1) on goods notes: non-canceled NF-e issued in the period with a federal
     * retention (retTrib), or purchases with ICMS-ST (informative).
     */
    @Query("select d from FiscalXmlDocument d join fetch d.company where d.company.id in :companyIds and d.deleted = false"
            + " and (d.status is null or d.status <> br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED)"
            + " and d.issueDate between :from and :to"
            + " and (d.retPis > 0 or d.retCofins > 0 or d.retCsll > 0 or d.retIrrf > 0 or d.retInss > 0"
            + "      or (d.ownIssue = false and d.valorIcmsSt > 0))")
    List<FiscalXmlDocument> findForRetentions(@Param("companyIds") Collection<UUID> companyIds,
                                              @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Purchases per supplier (curva ABC): CNPJ/CPF, name, value, notes. Canceled notes out. */
    @Query("select coalesce(d.issuerCnpj, d.emitenteCpf), max(d.emitenteNome), coalesce(sum(d.valorTotalNota), 0), count(d)"
            + " from FiscalXmlDocument d where d.company.id = :companyId and d.deleted = false"
            + " and d.direction = br.com.cshub.taxify.enumerated.FiscalDirection.INBOUND"
            + " and (d.status is null or d.status <> br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED)"
            + " and d.issueDate >= :from and d.issueDate < :to"
            + " group by coalesce(d.issuerCnpj, d.emitenteCpf) order by coalesce(sum(d.valorTotalNota), 0) desc")
    List<Object[]> sumPurchasesBySupplier(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** {@link #countOnlySieg}'s notes themselves, oldest first. */
    @Query("select d from FiscalXmlDocument d where d.company.id = :companyId and d.deleted = false"
            + " and d.ownIssue = false and d.foundInSiegAt < :before and d.foundInSefazAt is null"
            + " and d.issueDate >= :issuedSince order by d.issueDate asc")
    List<FiscalXmlDocument> findOnlySieg(@Param("companyId") UUID companyId, @Param("before") LocalDateTime before,
                                         @Param("issuedSince") LocalDateTime issuedSince, Pageable pageable);

    /** {@link #countOnlySefaz}'s notes themselves, oldest first. */
    @Query("select d from FiscalXmlDocument d where d.company.id = :companyId and d.deleted = false"
            + " and d.foundInSefazAt < :before and d.foundInSiegAt is null order by d.issueDate asc")
    List<FiscalXmlDocument> findOnlySefaz(@Param("companyId") UUID companyId, @Param("before") LocalDateTime before,
                                          Pageable pageable);

    /** Notes reviewed with a divergence, issued since {@code since}. */
    List<FiscalXmlDocument> findByCompany_IdAndDeletedIsFalseAndReviewStatusAndIssueDateGreaterThanEqualOrderByIssueDateAsc(
            UUID companyId, ReviewStatus reviewStatus, LocalDateTime since, Pageable pageable);

    /** SIEG x SEFAZ: notes SEFAZ handed over before {@code before} that SIEG never brought. */
    @Query("select count(d) from FiscalXmlDocument d where d.company.id = :companyId and d.deleted = false"
            + " and d.foundInSefazAt < :before and d.foundInSiegAt is null")
    long countOnlySefaz(@Param("companyId") UUID companyId, @Param("before") LocalDateTime before);

    /**
     * Period totals per direction - canceled notes don't count in the sums, only in their own
     * count. Row: direction, count, total, ICMS, ICMS-ST, IPI, PIS, COFINS, canceled count.
     */
    @Query("select d.direction,"
            + " sum(case when d.status = " + CANCELED + " then 0 else 1 end),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.valorTotalNota end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.valorIcms end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.valorIcmsSt end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.valorIpi end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.valorPis end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.valorCofins end), 0),"
            + " sum(case when d.status = " + CANCELED + " then 1 else 0 end)"
            + " from FiscalXmlDocument d where d.company.id = :companyId and d.deleted = false"
            + " and d.issueDate between :issueDateFrom and :issueDateTo"
            + " group by d.direction")
    List<Object[]> totalsByDirection(@Param("companyId") UUID companyId,
                                     @Param("issueDateFrom") LocalDateTime issueDateFrom,
                                     @Param("issueDateTo") LocalDateTime issueDateTo);

    /** Outbound (non-canceled) notes per company in [from, to). Row: company id, count. */
    @Query("select d.company.id, count(d) from FiscalXmlDocument d where d.company.id in :companyIds and d.deleted = false"
            + " and d.direction = br.com.cshub.taxify.enumerated.FiscalDirection.OUTBOUND"
            + " and (d.status is null or d.status <> " + CANCELED + ")"
            + " and d.issueDate >= :from and d.issueDate < :to group by d.company.id")
    List<Object[]> countOutboundByCompany(@Param("companyIds") Collection<UUID> companyIds,
                                          @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** First outbound note of each company - start of activity for the RBT12 proportionalization. Row: company id, date. */
    @Query("select d.company.id, min(d.issueDate) from FiscalXmlDocument d where d.company.id in :companyIds and d.deleted = false"
            + " and d.direction = br.com.cshub.taxify.enumerated.FiscalDirection.OUTBOUND"
            + " and (d.status is null or d.status <> " + CANCELED + ") group by d.company.id")
    List<Object[]> firstOutboundByCompany(@Param("companyIds") Collection<UUID> companyIds);

    /** The company's latest own-issued note - its issuer block completes the company registration. */
    Optional<FiscalXmlDocument> findFirstByCompany_IdAndOwnIssueTrueAndDeletedIsFalseOrderByIssueDateDesc(UUID companyId);

    /**
     * Entries of the month still to be reviewed, per company - the closing checklist.
     * Canceled notes don't need it. Row: company id, count.
     */
    @Query("select d.company.id, count(d) from FiscalXmlDocument d where d.company.id in :companyIds and d.deleted = false"
            + " and d.direction = br.com.cshub.taxify.enumerated.FiscalDirection.INBOUND"
            + " and (d.status is null or d.status <> " + CANCELED + ")"
            + " and (d.reviewStatus is null or d.reviewStatus = br.com.cshub.taxify.enumerated.ReviewStatus.PENDING)"
            + " and d.issueDate >= :from and d.issueDate < :to group by d.company.id")
    List<Object[]> countPendingReviewByCompany(@Param("companyIds") Collection<UUID> companyIds,
                                               @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Notes with a file still outside S3 (original or rectified), in id order after
     * {@code afterId} (ids are stored as text, so the keyset compares text) - the batches FiscalXmlStorageMigration copies to the bucket.
     */
    @Query("select d from FiscalXmlDocument d where cast(d.id as String) > :afterId"
            + " and ((d.xmlFilePath is not null and d.xmlFilePath not like 's3://%')"
            + "      or (d.rectifiedFilePath is not null and d.rectifiedFilePath not like 's3://%'))"
            + " order by cast(d.id as String)")
    List<FiscalXmlDocument> findWithFileOutsideS3(@Param("afterId") String afterId, Pageable pageable);

    /**
     * NFe notes still holding fields parsed by an older FiscalXmlMetadataParser (see
     * {@code FiscalXmlMetadataParser.CURRENT_VERSION}) - what FiscalXmlTaxDetailBackfill
     * re-reads. Paged by id ({@code afterId}, "" to start) so a note that fails to be read
     * is not picked again in the same run.
     */
    @Query("select d.id from FiscalXmlDocument d where d.deleted = false and d.documentType = 'NFE'"
            + " and d.xmlFilePath is not null"
            + " and (d.metadataVersion is null or d.metadataVersion < :version)"
            + " and cast(d.id as String) > :afterId"
            + " order by cast(d.id as String)")
    List<UUID> findIdsParsedBefore(@Param("version") int version, @Param("afterId") String afterId, Pageable pageable);
}
