package br.com.cshub.taxify.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * The origins of a document already stored (FD-RN-31, {@code fiscal_document_sources}), for the repositories of the
 * documents. "Seen again" is one atomic upsert on the unique key (document_id, source, retrieval_mode): two instances
 * (or the scheduler and a manual sync) marking the same known document never collide - a duplicate never fails the
 * page - and {@code last_seen_at} only moves forward (CT-FD-28). No {@code AS new} syntax (the local MySQL 5.7 lacks
 * it): the values come from the parameters. A new document gets its origins with it (the aggregate's cascade).
 */
@NoRepositoryBean
public interface FiscalDocumentSourceRepository {

    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO fiscal_document_sources (id, document_id, company_id, source, retrieval_mode, first_seen_at, last_seen_at)"
            + " VALUES (:id, :documentId, :companyId, :source, :mode, :at, :at)"
            + " ON DUPLICATE KEY UPDATE last_seen_at = GREATEST(last_seen_at, :at)", nativeQuery = true)
    int recordSeen(@Param("id") String id, @Param("documentId") String documentId, @Param("companyId") String companyId,
                   @Param("source") String source, @Param("mode") String mode, @Param("at") LocalDateTime at);
}
