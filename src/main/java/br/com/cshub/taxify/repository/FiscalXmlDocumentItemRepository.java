package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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

    /** Fetches each item's note with it - callers read the note of every item. */
    @EntityGraph(attributePaths = "document")
    List<FiscalXmlDocumentItem> findByDocument_Company_IdAndDocument_IssueDateBetween(
            UUID companyId, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalXmlDocumentItem> findByDocument_IdOrderByNumeroItemAsc(UUID documentId);

    long countByDocument_Id(UUID documentId);

    /** Each row is {documentId, itemCount} - one query for a whole page instead of N lazy loads. */
    @Query("select i.document.id, count(i) from FiscalXmlDocumentItem i where i.document.id in :documentIds group by i.document.id")
    List<Object[]> countByDocumentIds(@Param("documentIds") Collection<UUID> documentIds);

    /**
     * The company's "product table": one row per distinct product code across the items of
     * its stored notes - {codigo, descricao, ncm, cfop, ocorrencias}, most frequent first.
     * {@code term} is a lowercase {@code %...%} pattern matched against code and description
     * ({@code %%} for no filter). Description/NCM/CFOP come from max() since the same code
     * can appear with slightly different values across notes.
     */
    @Query("select i.codigo, max(i.descricao), max(i.ncm), max(i.cfop), count(i) from FiscalXmlDocumentItem i "
            + "where i.document.company.id = :companyId and i.document.deleted = false "
            + "and i.codigo is not null and i.codigo <> '' "
            + "and (lower(i.codigo) like :term or lower(i.descricao) like :term) "
            + "group by i.codigo order by count(i) desc, i.codigo asc")
    List<Object[]> findDistinctProductsByCompany(@Param("companyId") UUID companyId, @Param("term") String term, Pageable pageable);

    /**
     * How the company's products were classified on its notes: one row per product code and
     * distinct combination of fields, with how many items had it - what the rule suggestion
     * picks the most frequent value of each field from. The ICMS rate only counts internal
     * operations (an interstate rate isn't the product's). Canceled and deleted notes are out;
     * {@code direction} null = every note.
     * Row: codigo, descricao, ncm, cfop, origem, cst (null when CSOSN), csosn, internal pICMS, cest,
     * cst IPI, pIPI, cst PIS, pPIS, cst COFINS, pCOFINS, cst IBS/CBS, cClassTrib, count.
     */
    @Query("select i.codigo, max(i.descricao), i.ncm, i.cfop, i.taxDetail.origemMercadoria,"
            + " case when i.taxDetail.csosn is null then i.situacaoTributariaIcms end, i.taxDetail.csosn,"
            + " case when d.destinoOperacao = '1' then i.aliquotaIcms end, i.taxDetail.cest,"
            + " i.situacaoTributariaIpi, i.taxDetail.aliquotaIpi, i.situacaoTributariaPis, i.taxDetail.aliquotaPis,"
            + " i.situacaoTributariaCofins, i.taxDetail.aliquotaCofins, i.ibsCbs.cst, i.ibsCbs.classificacaoTributaria, count(i)"
            + " from FiscalXmlDocumentItem i join i.document d"
            + " where d.company.id = :companyId and d.deleted = false"
            + " and (d.status is null or d.status <> br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED)"
            + " and (:direction is null or d.direction = :direction)"
            + " and i.codigo is not null and i.codigo <> ''"
            + " group by i.codigo, i.ncm, i.cfop, i.taxDetail.origemMercadoria,"
            + " case when i.taxDetail.csosn is null then i.situacaoTributariaIcms end, i.taxDetail.csosn,"
            + " case when d.destinoOperacao = '1' then i.aliquotaIcms end, i.taxDetail.cest,"
            + " i.situacaoTributariaIpi, i.taxDetail.aliquotaIpi, i.situacaoTributariaPis, i.taxDetail.aliquotaPis,"
            + " i.situacaoTributariaCofins, i.taxDetail.aliquotaCofins, i.ibsCbs.cst, i.ibsCbs.classificacaoTributaria")
    List<Object[]> findClassificationsByProduct(@Param("companyId") UUID companyId,
                                                @Param("direction") br.com.cshub.taxify.enumerated.FiscalDirection direction);

    /**
     * Item values per company, month, direction and CFOP - the input of the period
     * assessment (TaxAssessmentService). Canceled and deleted notes are out.
     * Row: company id, year, month, direction, cfop, sum of item values.
     */
    @Query("select d.company.id, year(d.issueDate), month(d.issueDate), d.direction, i.cfop, coalesce(sum(i.valorTotal), 0)"
            + " from FiscalXmlDocumentItem i join i.document d"
            + " where d.company.id in :companyIds and d.deleted = false and d.direction is not null"
            + " and (d.status is null or d.status <> br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED)"
            + " and d.issueDate >= :from and d.issueDate < :to"
            + " group by d.company.id, year(d.issueDate), month(d.issueDate), d.direction, i.cfop")
    List<Object[]> sumByCompanyMonthAndCfop(@Param("companyIds") Collection<UUID> companyIds,
                                           @Param("from") java.time.LocalDateTime from,
                                           @Param("to") java.time.LocalDateTime to);
}
