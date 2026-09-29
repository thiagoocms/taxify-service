package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface FiscalXmlDocumentItemRepository extends JpaRepository<FiscalXmlDocumentItem, UUID> {

    List<FiscalXmlDocumentItem> findByDocument_Company_IdAndDocument_IssueDateBetween(
            UUID companyId, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalXmlDocumentItem> findByDocument_IdOrderByNumeroItemAsc(UUID documentId);

    long countByDocument_Id(UUID documentId);

    /** Each row is {documentId, itemCount} - one query for a whole page instead of N lazy loads. */
    @Query("select i.document.id, count(i) from FiscalXmlDocumentItem i where i.document.id in :documentIds group by i.document.id")
    List<Object[]> countByDocumentIds(@Param("documentIds") Collection<UUID> documentIds);
}
