package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every fiscal document, any type ({@code from FiscalDocument}, FD-RN-04). The "XML documents" queries are the ones of the
 * notes screen, the export and the purge: NF-e, NFC-e and CT-e ({@code type(d) in (Nfe, Nfce, Cte)}) - what the old
 * old notes table held (FD-RN-15); the NFS-e have their own screen ({@link NfseRepository}).
 */
@Repository
public interface FiscalDocumentRepository extends AbstractJpaRepository<FiscalDocument, UUID> {

    /** The idempotency of the import (FD-RN-12): one document per company and access key, any type. */
    boolean existsByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    Optional<FiscalDocument> findFirstByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    /** An NF-e, NFC-e or CT-e by id, not deleted - an NFS-e id is "not found" here (FD-RN-17). */
    @Query("select d from FiscalDocument d where d.id = :id and d.deleted = false and type(d) in (Nfe, Nfce, Cte)")
    Optional<FiscalDocument> findXmlDocument(@Param("id") UUID id);

    /** The NF-e, NFC-e and CT-e of these ids, not deleted. */
    @Query("select d from FiscalDocument d where d.id in :ids and d.deleted = false and type(d) in (Nfe, Nfce, Cte)")
    List<FiscalDocument> findXmlDocuments(@Param("ids") java.util.Collection<UUID> ids);

    /** The company's NF-e, NFC-e and CT-e issued in [from, to], latest first - the export and the rectification job. */
    @Query("select d from FiscalDocument d where d.company.id = :companyId and type(d) in (Nfe, Nfce, Cte)"
            + " and d.issueDate between :from and :to order by d.issueDate desc")
    List<FiscalDocument> findXmlDocumentsIssuedBetween(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    /**
     * The notes screen. Every filter is optional (null = not applied): {@code number} exact, {@code direction}
     * entry/exit, {@code participant} a lower-case piece of the issuer's or recipient's name, or digits of their
     * document, {@code cfop} some item with it as its effective CFOP - the rectified one when the note has a rectified XML
     * (FD-RN-57, decision of 2026-11-28), stored without spaces (spec 3.6) -, {@code source} the first origin
     * ({@code source_system}). {@code onlySiegBefore}: seen by SIEG before it and never by SEFAZ, a third-party note;
     * {@code onlySefazBefore}: seen by SEFAZ before it and never by SIEG ({@code fiscal_document_sources}, FD-RN-13).
     */
    @Query("select d from FiscalDocument d where d.company.id = :companyId and d.deleted = false and type(d) in (Nfe, Nfce, Cte)"
            + " and d.issueDate between :issueDateFrom and :issueDateTo"
            + " and (:number is null or d.number = :number)"
            + " and (:direction is null or d.direction = :direction)"
            + " and (:participant is null"
            + "      or lower(d.issuer.name) like :participant or lower(d.recipient.name) like :participant"
            + "      or d.issuer.document like :participant or d.recipient.document like :participant)"
            + " and (:reviewStatus is null"
            + "      or coalesce(d.review.status, br.com.cshub.taxify.enumerated.ReviewStatus.PENDING) = :reviewStatus)"
            + " and (:source is null or d.sourceSystem = :source)"
            + " and (:onlySiegBefore is null or (d.companyRole <> br.com.cshub.taxify.enumerated.CompanyDocumentRole.ISSUER"
            + "      and exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sieg"
            + "                  and s.firstSeenAt < :onlySiegBefore)"
            + "      and not exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sefaz)))"
            + " and (:onlySefazBefore is null"
            + "      or (exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sefaz"
            + "                  and s.firstSeenAt < :onlySefazBefore)"
            + "      and not exists (select s.id from FiscalDocumentSource s where s.document = d and s.source = :sieg)))"
            + " and (:cfop is null or exists (select i.id from FiscalDocumentItem i where i.document = d and i.effectiveCfop = :cfop))")
    Page<FiscalDocument> search(@Param("companyId") UUID companyId,
                                @Param("issueDateFrom") LocalDateTime issueDateFrom,
                                @Param("issueDateTo") LocalDateTime issueDateTo,
                                @Param("number") String number,
                                @Param("direction") FiscalDirection direction,
                                @Param("participant") String participant,
                                @Param("reviewStatus") ReviewStatus reviewStatus,
                                @Param("source") DocumentSourceKey source,
                                @Param("onlySiegBefore") LocalDateTime onlySiegBefore,
                                @Param("onlySefazBefore") LocalDateTime onlySefazBefore,
                                @Param("cfop") String cfop,
                                @Param("sieg") DocumentSourceKey sieg,
                                @Param("sefaz") DocumentSourceKey sefaz,
                                Pageable pageable);

    /**
     * NF-e, NFC-e and CT-e with a file still outside S3 (original or rectified), in id order after {@code afterId} (ids are
     * text, so the keyset compares text) - the batches FiscalXmlStorageMigration copies to the bucket.
     */
    @Query("select d from FiscalDocument d where type(d) in (Nfe, Nfce, Cte) and cast(d.id as String) > :afterId"
            + " and ((d.xmlFilePath is not null and d.xmlFilePath not like 's3://%')"
            + "      or (treat(d as GoodsInvoice).rectifiedFilePath is not null"
            + "          and treat(d as GoodsInvoice).rectifiedFilePath not like 's3://%'))"
            + " order by cast(d.id as String)")
    List<FiscalDocument> findWithFileOutsideS3(@Param("afterId") String afterId, Pageable pageable);

    /**
     * FD-RN-16: NF-e, NFC-e and CT-e whose XML file was not purged yet, issued before {@code cutoff}, in id order after
     * {@code afterId} (keyset, a page at a time) - index idx_fd_purge (xml_purged_at, issue_date). NFS-e stay out (P-FD-05).
     */
    @Query("select d from FiscalDocument d where type(d) in (Nfe, Nfce, Cte) and d.deleted = false"
            + " and d.xmlPurgedAt is null and d.issueDate < :cutoff and cast(d.id as String) > :afterId"
            + " order by cast(d.id as String)")
    List<FiscalDocument> findPurgeCandidates(@Param("cutoff") LocalDateTime cutoff, @Param("afterId") String afterId,
                                             Pageable pageable);
}
