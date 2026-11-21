package br.com.cshub.taxify.repository;

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

    boolean existsByAccessKey(String accessKey);

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
            + "      or d.emitenteCpf like :participant or d.destinatarioCpf like :participant)")
    Page<FiscalXmlDocument> search(@Param("companyId") UUID companyId,
                                   @Param("issueDateFrom") LocalDateTime issueDateFrom,
                                   @Param("issueDateTo") LocalDateTime issueDateTo,
                                   @Param("numero") String numero,
                                   @Param("direction") FiscalDirection direction,
                                   @Param("participant") String participant,
                                   Pageable pageable);

    /**
     * Period totals per direction (count, value and main taxes) - the cards above the list.
     * Row: direction, count, total, ICMS, ICMS-ST, IPI, PIS, COFINS.
     */
    @Query("select d.direction, count(d), coalesce(sum(d.valorTotalNota), 0), coalesce(sum(d.valorIcms), 0),"
            + " coalesce(sum(d.valorIcmsSt), 0), coalesce(sum(d.valorIpi), 0), coalesce(sum(d.valorPis), 0),"
            + " coalesce(sum(d.valorCofins), 0)"
            + " from FiscalXmlDocument d where d.company.id = :companyId and d.deleted = false"
            + " and d.issueDate between :issueDateFrom and :issueDateTo"
            + " group by d.direction")
    List<Object[]> totalsByDirection(@Param("companyId") UUID companyId,
                                     @Param("issueDateFrom") LocalDateTime issueDateFrom,
                                     @Param("issueDateTo") LocalDateTime issueDateTo);
}
