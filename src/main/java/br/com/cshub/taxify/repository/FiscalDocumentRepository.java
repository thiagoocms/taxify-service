package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
public interface FiscalDocumentRepository extends AbstractJpaRepository<FiscalDocument, UUID>, FiscalDocumentSourceRepository {

    /**
     * The purge's mark (FD-RN-16), in a transaction of its own, before the document's files are deleted: a failure
     * later never undoes it with the files already gone. 1 = marked now, 0 = already marked (another run).
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "UPDATE fiscal_documents SET xml_purged_at = :at, last_modified_by = 'system', last_modified_date = :at"
            + " WHERE id = :id AND xml_purged_at IS NULL", nativeQuery = true)
    int markXmlPurged(@Param("id") String id, @Param("at") LocalDateTime at);

    /** Undoes {@link #markXmlPurged} when the files could not be deleted (the next run tries again). */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = "UPDATE fiscal_documents SET xml_purged_at = NULL WHERE id = :id AND xml_purged_at = :at", nativeQuery = true)
    int unmarkXmlPurged(@Param("id") String id, @Param("at") LocalDateTime at);

    /** The idempotency of the import (FD-RN-12): one document per company and access key, any type. */
    boolean existsByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    Optional<FiscalDocument> findFirstByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    /** An NF-e, NFC-e or CT-e by id, not deleted - an NFS-e id is "not found" here (FD-RN-17). */
    @Query("select d from FiscalDocument d where d.id = :id and d.deleted = false and type(d) in (Nfe, Nfce, Cte)")
    Optional<FiscalDocument> findXmlDocument(@Param("id") UUID id);

    /** The NF-e, NFC-e and CT-e of these ids, not deleted. */
    @Query("select d from FiscalDocument d where d.id in :ids and d.deleted = false and type(d) in (Nfe, Nfce, Cte)")
    List<FiscalDocument> findXmlDocuments(@Param("ids") java.util.Collection<UUID> ids);

    /** The company's NF-e, NFC-e and CT-e issued in [from, to), latest first - the export and the rectification job. */
    @Query("select d from FiscalDocument d where d.company.id = :companyId and type(d) in (Nfe, Nfce, Cte)"
            + " and d.issueDate >= :from and d.issueDate < :to order by d.issueDate desc")
    List<FiscalDocument> findXmlDocumentsIssuedBetween(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    /**
     * The notes screen: NF-e, NFC-e and CT-e matching {@link FiscalDocumentSearchFilter#WHERE} - the same predicate as
     * the cards (CF-RN-10). A CT-e has no items, so it never matches a CFOP filter (CF-RN-08).
     */
    @Query("select d from FiscalDocument d where type(d) in (Nfe, Nfce, Cte) and" + FiscalDocumentSearchFilter.WHERE)
    Page<FiscalDocument> search(@Param(FiscalDocumentSearchFilter.PARAM) FiscalDocumentSearchFilter filter, Pageable pageable);

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
