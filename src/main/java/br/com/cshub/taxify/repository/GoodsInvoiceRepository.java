package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.enumerated.RetrievalMode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * NF-e and NFC-e ({@code from GoodsInvoice}, FD-RN-04/14): what every fiscal routine reads - the old queries that filtered
 * {@code document_type = 'NFE'} without the model, and the sums and counts of the goods notes. Hibernate adds
 * {@code document_type in ('NFE','NFCE')}; the indexes of spec 3.8 start with company and type. "Canceled" is
 * {@code status = CANCELED} (FD-RN-10, never null any more). {@code issued} is the side of the company: the company
 * issued the note ({@code company_role = ISSUER}) or not.
 */
@Repository
public interface GoodsInvoiceRepository extends AbstractJpaRepository<GoodsInvoice, UUID> {

    String CANCELED = "br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED";
    String ISSUER = "br.com.cshub.taxify.enumerated.CompanyDocumentRole.ISSUER";
    String INBOUND = "br.com.cshub.taxify.enumerated.FiscalDirection.INBOUND";
    String OUTBOUND = "br.com.cshub.taxify.enumerated.FiscalDirection.OUTBOUND";
    String SIDE = " and ((:issued = true and d.companyRole = " + ISSUER + ") or (:issued = false and d.companyRole <> " + ISSUER + "))";

    /**
     * What a provider itself delivered of a sync side in a window, in the period mode (SIEG's skip and the side's
     * "importado", B1): the note has the origin {@code source} with {@code retrieval_mode = PERIOD} (spec 3.10).
     */
    @Query("select count(d) from GoodsInvoice d where d.company.id = :companyId"
            + " and d.issueDate between :from and :to" + SIDE
            + " and exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :source"
            + "             and s.retrievalMode = :mode)")
    long countDeliveredBy(@Param("companyId") UUID companyId, @Param("issued") boolean issued,
                          @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                          @Param("source") DocumentSourceKey source, @Param("mode") RetrievalMode mode);

    /** What a sync side holds in a window from any source - shown on the capture panel next to the provider's numbers. */
    @Query("select count(d) from GoodsInvoice d where d.company.id = :companyId and d.issueDate between :from and :to" + SIDE)
    long countStored(@Param("companyId") UUID companyId, @Param("issued") boolean issued,
                     @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Whether a side has a note issued in the window - the daily event sweep asks SIEG only then (A2). */
    @Query("select count(d) > 0 from GoodsInvoice d where d.company.id = :companyId and d.issueDate between :from and :to" + SIDE)
    boolean existsIssuedBetween(@Param("companyId") UUID companyId, @Param("issued") boolean issued,
                                @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Fronteira PE: the notes an e-Fisco extract lists, found by issuer CNPJ + number (the extract has no access key). */
    @Query("select d from GoodsInvoice d where d.company.id = :companyId and d.deleted = false"
            + " and d.issuer.documentType = br.com.cshub.taxify.enumerated.TaxpayerDocumentType.CNPJ"
            + " and d.issuer.document in :issuerCnpjs and d.number in :numbers")
    List<GoodsInvoice> findByIssuerCnpjsAndNumbers(@Param("companyId") UUID companyId,
                                                   @Param("issuerCnpjs") Collection<String> issuerCnpjs,
                                                   @Param("numbers") Collection<String> numbers);

    /**
     * The retentions' source (M1) on goods notes: non-canceled notes issued in the period with a federal retention
     * (retTrib), or purchases with ICMS-ST (informative).
     */
    @Query("select d from GoodsInvoice d join fetch d.company where d.company.id in :companyIds and d.deleted = false"
            + " and d.status <> " + CANCELED + " and d.issueDate between :from and :to"
            + " and (d.withholdings.pis > 0 or d.withholdings.cofins > 0 or d.withholdings.csll > 0"
            + "      or d.withholdings.irrf > 0 or d.withholdings.inss > 0"
            + "      or (d.companyRole <> " + ISSUER + " and d.goodsTotals.icmsStAmount > 0))")
    List<GoodsInvoice> findForRetentions(@Param("companyIds") Collection<UUID> companyIds,
                                         @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Purchases per supplier (curva ABC): document, name, value, notes. Canceled notes out. */
    @Query("select d.issuer.document, max(d.issuer.name), coalesce(sum(d.totalAmount), 0), count(d)"
            + " from GoodsInvoice d where d.company.id = :companyId and d.deleted = false"
            + " and d.direction = " + INBOUND + " and d.status <> " + CANCELED
            + " and d.issueDate >= :from and d.issueDate < :to"
            + " group by d.issuer.document order by coalesce(sum(d.totalAmount), 0) desc")
    List<Object[]> sumPurchasesBySupplier(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /**
     * SIEG x SEFAZ (FD-RN-13): third-party notes {@code sieg} brought before {@code before}, issued since
     * {@code issuedSince}, never seen by {@code sefaz}.
     */
    @Query("select count(d) from GoodsInvoice d where d.company.id = :companyId and d.deleted = false"
            + " and d.companyRole <> " + ISSUER + " and d.issueDate >= :issuedSince"
            + " and exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sieg and s.firstSeenAt < :before)"
            + " and not exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sefaz)")
    long countOnlySieg(@Param("companyId") UUID companyId, @Param("before") LocalDateTime before,
                       @Param("issuedSince") LocalDateTime issuedSince, @Param("sieg") DocumentSourceKey sieg,
                       @Param("sefaz") DocumentSourceKey sefaz);

    /** {@link #countOnlySieg}'s notes themselves, oldest first. */
    @Query("select d from GoodsInvoice d where d.company.id = :companyId and d.deleted = false"
            + " and d.companyRole <> " + ISSUER + " and d.issueDate >= :issuedSince"
            + " and exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sieg and s.firstSeenAt < :before)"
            + " and not exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sefaz)"
            + " order by d.issueDate asc")
    List<GoodsInvoice> findOnlySieg(@Param("companyId") UUID companyId, @Param("before") LocalDateTime before,
                                    @Param("issuedSince") LocalDateTime issuedSince, @Param("sieg") DocumentSourceKey sieg,
                                    @Param("sefaz") DocumentSourceKey sefaz, Pageable pageable);

    /** SIEG x SEFAZ: notes {@code sefaz} handed over before {@code before} that {@code sieg} never brought. */
    @Query("select count(d) from GoodsInvoice d where d.company.id = :companyId and d.deleted = false"
            + " and exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sefaz and s.firstSeenAt < :before)"
            + " and not exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sieg)")
    long countOnlySefaz(@Param("companyId") UUID companyId, @Param("before") LocalDateTime before,
                        @Param("sieg") DocumentSourceKey sieg, @Param("sefaz") DocumentSourceKey sefaz);

    /** {@link #countOnlySefaz}'s notes themselves, oldest first. */
    @Query("select d from GoodsInvoice d where d.company.id = :companyId and d.deleted = false"
            + " and exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sefaz and s.firstSeenAt < :before)"
            + " and not exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sieg)"
            + " order by d.issueDate asc")
    List<GoodsInvoice> findOnlySefaz(@Param("companyId") UUID companyId, @Param("before") LocalDateTime before,
                                     @Param("sieg") DocumentSourceKey sieg, @Param("sefaz") DocumentSourceKey sefaz,
                                     Pageable pageable);

    /**
     * The sums of the cards per direction - canceled notes don't count in the sums, only in their own count. Row:
     * direction, count, total, ICMS, ICMS-ST, IPI, PIS, COFINS, canceled count.
     */
    String TOTALS_BY_DIRECTION = "select d.direction,"
            + " sum(case when d.status = " + CANCELED + " then 0 else 1 end),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.totalAmount end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.goodsTotals.icmsAmount end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.goodsTotals.icmsStAmount end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.goodsTotals.ipiAmount end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.goodsTotals.pisAmount end), 0),"
            + " coalesce(sum(case when d.status = " + CANCELED + " then 0 else d.goodsTotals.cofinsAmount end), 0),"
            + " sum(case when d.status = " + CANCELED + " then 1 else 0 end)"
            + " from GoodsInvoice d where";

    /** Period totals per direction, every note of the company and period ({@link #TOTALS_BY_DIRECTION}) - the reports. */
    @Query(TOTALS_BY_DIRECTION + " d.company.id = :companyId and d.deleted = false"
            + " and d.issueDate between :issueDateFrom and :issueDateTo"
            + " group by d.direction")
    List<Object[]> totalsByDirection(@Param("companyId") UUID companyId,
                                     @Param("issueDateFrom") LocalDateTime issueDateFrom,
                                     @Param("issueDateTo") LocalDateTime issueDateTo);

    /**
     * The cards of the notes screen ({@link #TOTALS_BY_DIRECTION}): the NF-e and NFC-e matching the list's predicate
     * ({@link FiscalDocumentSearchFilter#WHERE}, CF-RN-10) - the whole note of each one (CF-RN-12). The caller passes
     * the filter without the tab (CF-RN-11).
     */
    @Query(TOTALS_BY_DIRECTION + FiscalDocumentSearchFilter.WHERE + " group by d.direction")
    List<Object[]> totalsByDirectionFiltered(@Param(FiscalDocumentSearchFilter.PARAM) FiscalDocumentSearchFilter filter);

    /** Outbound (non-canceled) notes per company in [from, to). Row: company id, count. */
    @Query("select d.company.id, count(d) from GoodsInvoice d where d.company.id in :companyIds and d.deleted = false"
            + " and d.direction = " + OUTBOUND + " and d.status <> " + CANCELED
            + " and d.issueDate >= :from and d.issueDate < :to group by d.company.id")
    List<Object[]> countOutboundByCompany(@Param("companyIds") Collection<UUID> companyIds,
                                          @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** First outbound note of each company - start of activity for the RBT12 proportionalization. Row: company id, date. */
    @Query("select d.company.id, min(d.issueDate) from GoodsInvoice d where d.company.id in :companyIds and d.deleted = false"
            + " and d.direction = " + OUTBOUND + " and d.status <> " + CANCELED + " group by d.company.id")
    List<Object[]> firstOutboundByCompany(@Param("companyIds") Collection<UUID> companyIds);

    /** The company's latest own-issued note - its issuer block completes the company registration. */
    @Query("select d from GoodsInvoice d where d.company.id = :companyId and d.deleted = false and d.companyRole = " + ISSUER
            + " order by d.issueDate desc limit 1")
    Optional<GoodsInvoice> findLatestOwnIssue(@Param("companyId") UUID companyId);

    /**
     * Entries of the month still to be reviewed, per company - the closing checklist. Canceled notes don't need it.
     * Row: company id, count.
     */
    @Query("select d.company.id, count(d) from GoodsInvoice d where d.company.id in :companyIds and d.deleted = false"
            + " and d.direction = " + INBOUND + " and d.status <> " + CANCELED
            + " and (d.review.status is null or d.review.status = br.com.cshub.taxify.enumerated.ReviewStatus.PENDING)"
            + " and d.issueDate >= :from and d.issueDate < :to group by d.company.id")
    List<Object[]> countPendingReviewByCompany(@Param("companyIds") Collection<UUID> companyIds,
                                               @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Notes reviewed with a divergence (or any review status), issued since {@code since}, oldest first. */
    @Query("select d from GoodsInvoice d where d.company.id = :companyId and d.deleted = false"
            + " and d.review.status = :reviewStatus and d.issueDate >= :since order by d.issueDate asc")
    List<GoodsInvoice> findReviewedSince(@Param("companyId") UUID companyId,
                                         @Param("reviewStatus") br.com.cshub.taxify.enumerated.ReviewStatus reviewStatus,
                                         @Param("since") LocalDateTime since, Pageable pageable);

    /** The company's NF-e and NFC-e issued in [from, to], latest first - the rectification job. */
    @Query("select d from GoodsInvoice d where d.company.id = :companyId and d.issueDate between :from and :to"
            + " order by d.issueDate desc")
    List<GoodsInvoice> findIssuedBetween(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    /**
     * Notes still holding fields parsed by an older FiscalXmlMetadataParser - what FiscalXmlTaxDetailBackfill re-reads.
     * Paged by id ({@code afterId}, "" to start) so a note that fails to be read is not picked again in the same run.
     * Index idx_fd_type_metadata_version (document_type, metadata_version).
     */
    @Query("select d.id from GoodsInvoice d where d.deleted = false and d.xmlFilePath is not null"
            + " and d.metadataVersion < :version and cast(d.id as String) > :afterId"
            + " order by cast(d.id as String)")
    List<UUID> findIdsParsedBefore(@Param("version") int version, @Param("afterId") String afterId, Pageable pageable);
}
