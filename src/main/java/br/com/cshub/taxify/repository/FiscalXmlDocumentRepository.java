package br.com.cshub.taxify.repository;

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

    boolean existsByAccessKey(String accessKey);

    long countByCompany_Id(UUID companyId);

    long countByCompany_IdAndIssueDateBetween(UUID companyId, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalXmlDocument> findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(
            UUID companyId, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalXmlDocument> findByIssueDateBeforeAndDeletedIsFalse(LocalDateTime cutoff);

    /** {@code numero} null = no number filter. */
    @Query("select d from FiscalXmlDocument d where d.company.id = :companyId"
            + " and d.issueDate between :issueDateFrom and :issueDateTo"
            + " and (:numero is null or d.numero = :numero)")
    Page<FiscalXmlDocument> search(@Param("companyId") UUID companyId,
                                   @Param("issueDateFrom") LocalDateTime issueDateFrom,
                                   @Param("issueDateTo") LocalDateTime issueDateTo,
                                   @Param("numero") String numero,
                                   Pageable pageable);
}
