package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.enumerated.FiscalXmlDirection;
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

    /** Dedupe is per company now - the same note can legitimately belong to two companies. */
    boolean existsByCompany_IdAndAccessKey(UUID companyId, String accessKey);

    long countByCompany_Id(UUID companyId);

    long countByCompany_IdAndDirecaoAndIssueDateBetween(UUID companyId, FiscalXmlDirection direcao,
                                                        LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalXmlDocument> findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(
            UUID companyId, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalXmlDocument> findByCompany_IdAndDirecaoAndIssueDateBetweenOrderByIssueDateDesc(
            UUID companyId, FiscalXmlDirection direcao, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalXmlDocument> findByIssueDateBeforeAndDeletedIsFalse(LocalDateTime cutoff);

    /** {@code numero}/{@code direcao} null = no filter on them. */
    @Query("select d from FiscalXmlDocument d where d.company.id = :companyId"
            + " and d.issueDate between :issueDateFrom and :issueDateTo"
            + " and (:numero is null or d.numero = :numero)"
            + " and (:direcao is null or d.direcao = :direcao)")
    Page<FiscalXmlDocument> search(@Param("companyId") UUID companyId,
                                   @Param("issueDateFrom") LocalDateTime issueDateFrom,
                                   @Param("issueDateTo") LocalDateTime issueDateTo,
                                   @Param("numero") String numero,
                                   @Param("direcao") FiscalXmlDirection direcao,
                                   Pageable pageable);
}
