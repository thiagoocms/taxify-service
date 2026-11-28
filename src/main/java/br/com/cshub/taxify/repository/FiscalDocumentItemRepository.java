package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
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

/**
 * Items of the goods invoices (NF-e and NFC-e, FD-RN-06). The CFOP is stored without spaces (spec 3.6), so the queries
 * compare and group it directly; "canceled" is {@code status = CANCELED} (FD-RN-10). Covering index
 * idx_fdi_document_cfop_amount (document_id, cfop, total_amount).
 */
@Repository
public interface FiscalDocumentItemRepository extends JpaRepository<FiscalDocumentItem, UUID> {

    String CANCELED = "br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED";
    String ISSUER = "br.com.cshub.taxify.enumerated.CompanyDocumentRole.ISSUER";

    /** Fetches each item's note with it - callers read the note of every item. */
    @EntityGraph(attributePaths = "document")
    List<FiscalDocumentItem> findByDocument_Company_IdAndDocument_IssueDateBetween(
            UUID companyId, LocalDateTime issueDateFrom, LocalDateTime issueDateTo);

    List<FiscalDocumentItem> findByDocument_IdOrderByItemNumberAsc(UUID documentId);

    /**
     * The notes a product rule can rectify: the company's notes received since {@code since}, not canceled, with an item
     * of that product code - from that supplier (its CNPJ or CPF) when {@code supplier} is given.
     */
    @Query("select distinct i.document.id from FiscalDocumentItem i where i.document.company.id = :companyId"
            + " and i.document.deleted = false and i.product.code = :code and i.document.issueDate >= :since"
            + " and i.document.companyRole <> " + ISSUER
            + " and i.document.status <> " + CANCELED
            + " and (:supplier is null or i.document.issuer.document = :supplier)")
    List<UUID> findRectifiableDocumentIdsWithProduct(@Param("companyId") UUID companyId, @Param("code") String code,
                                                     @Param("supplier") String supplier, @Param("since") LocalDateTime since);

    /** The items of several notes, each with its note - the list's divergence badge checks a page (or a period) at once. */
    @EntityGraph(attributePaths = "document")
    List<FiscalDocumentItem> findByDocument_IdIn(Collection<UUID> documentIds);

    long countByDocument_Id(UUID documentId);

    /** Each row is {documentId, itemCount} - one query for a whole page instead of N lazy loads. */
    @Query("select i.document.id, count(i) from FiscalDocumentItem i where i.document.id in :documentIds group by i.document.id")
    List<Object[]> countByDocumentIds(@Param("documentIds") Collection<UUID> documentIds);

    /**
     * Each row is {documentId, effective CFOP} (FD-RN-57) - the CFOPs of the version in effect of a whole page of notes
     * in one query, as the notes screen shows them and its filter finds them.
     */
    @Query("select distinct i.document.id, i.effectiveCfop from FiscalDocumentItem i"
            + " where i.document.id in :documentIds and i.effectiveCfop is not null")
    List<Object[]> findDistinctEffectiveCfopsByDocumentIds(@Param("documentIds") Collection<UUID> documentIds);

    /** Each row is {documentId, product code, cfop} - the CFOPs of a whole page of notes in one query, per product. */
    @Query("select distinct i.document.id, i.product.code, i.product.cfop from FiscalDocumentItem i"
            + " where i.document.id in :documentIds and i.product.cfop is not null")
    List<Object[]> findDistinctCfopsByDocumentIds(@Param("documentIds") Collection<UUID> documentIds);

    /**
     * The company's "product table": one row per distinct product code across the items of its stored notes -
     * {code, description, ncm, cfop, occurrences}, most frequent first. {@code term} is a lowercase {@code %...%}
     * pattern matched against code and description ({@code %%} for no filter).
     */
    @Query("select i.product.code, max(i.product.description), max(i.product.ncm), max(i.product.cfop), count(i)"
            + " from FiscalDocumentItem i"
            + " where i.document.company.id = :companyId and i.document.deleted = false"
            + " and i.product.code is not null and i.product.code <> ''"
            + " and (lower(i.product.code) like :term or lower(i.product.description) like :term)"
            + " group by i.product.code order by count(i) desc, i.product.code asc")
    List<Object[]> findDistinctProductsByCompany(@Param("companyId") UUID companyId, @Param("term") String term, Pageable pageable);

    /**
     * How the company's products were classified on the notes it received: one row per product code and distinct
     * combination of fields, with how many items had it. The ICMS rate only counts internal operations. Canceled and
     * deleted notes are out, and so are the company's own notes.
     * Row: code, description, ncm, cfop, origin, cst (null when CSOSN), csosn, internal pICMS, cest, cst IPI, pIPI,
     * cst PIS, pPIS, cst COFINS, pCOFINS, cst IBS/CBS, cClassTrib, supplier document, supplier name, count.
     */
    @Query("select i.product.code, max(i.product.description), i.product.ncm, i.product.cfop, i.icms.origin,"
            + " case when i.icms.csosn is null then i.icms.cst end, i.icms.csosn,"
            + " case when d.header.destinationType = '1' then i.icms.rate end, i.product.cest,"
            + " i.ipi.cst, i.ipi.rate, i.pis.cst, i.pis.rate, i.cofins.cst, i.cofins.rate, i.ibsCbs.cst, i.ibsCbs.classCode,"
            + " d.issuer.document, max(d.issuer.name), count(i)"
            + " from FiscalDocumentItem i join i.document d"
            + " where d.company.id = :companyId and d.deleted = false and d.status <> " + CANCELED
            + " and d.companyRole <> " + ISSUER
            + " and i.product.code is not null and i.product.code <> ''"
            + " group by i.product.code, i.product.ncm, i.product.cfop, i.icms.origin,"
            + " case when i.icms.csosn is null then i.icms.cst end, i.icms.csosn,"
            + " case when d.header.destinationType = '1' then i.icms.rate end, i.product.cest,"
            + " i.ipi.cst, i.ipi.rate, i.pis.cst, i.pis.rate, i.cofins.cst, i.cofins.rate, i.ibsCbs.cst, i.ibsCbs.classCode,"
            + " d.issuer.document")
    List<Object[]> findReceivedClassificationsByProduct(@Param("companyId") UUID companyId);

    /**
     * IBS and CBS of the items of the period per company and direction - the informative IBS/CBS block of the
     * assessment. Only items with an IBS/CBS group; canceled and deleted notes are out. vIBS falls back to
     * vIBSUF + vIBSMun when the note leaves it out. Row: company id, direction, sum of IBS, sum of CBS, number of notes.
     */
    @Query("select d.company.id, d.direction,"
            + " coalesce(sum(coalesce(i.ibsCbs.ibsAmount, coalesce(i.ibsCbs.stateAmount, 0) + coalesce(i.ibsCbs.cityAmount, 0))), 0),"
            + " coalesce(sum(i.ibsCbs.cbsAmount), 0), count(distinct d.id)"
            + " from FiscalDocumentItem i join i.document d"
            + " where d.company.id in :companyIds and d.deleted = false and d.direction is not null"
            + " and d.status <> " + CANCELED
            + " and i.ibsCbs.cst is not null"
            + " and d.issueDate >= :from and d.issueDate < :to"
            + " group by d.company.id, d.direction")
    List<Object[]> sumIbsCbsByCompanyAndDirection(@Param("companyIds") Collection<UUID> companyIds,
                                                  @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Item values per company, month, direction and CFOP - the input of the period assessment (TaxAssessmentService).
     * Canceled and deleted notes are out. Row: company id, year, month, direction, cfop, sum of item values.
     */
    @Query("select d.company.id, year(d.issueDate), month(d.issueDate), d.direction, i.product.cfop, coalesce(sum(i.totalAmount), 0)"
            + " from FiscalDocumentItem i join i.document d"
            + " where d.company.id in :companyIds and d.deleted = false and d.direction is not null"
            + " and d.status <> " + CANCELED
            + " and d.issueDate >= :from and d.issueDate < :to"
            + " group by d.company.id, year(d.issueDate), month(d.issueDate), d.direction, i.product.cfop")
    List<Object[]> sumByCompanyMonthAndCfop(@Param("companyIds") Collection<UUID> companyIds,
                                           @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Purchases per product (curva ABC): code, description, NCM, value, notes. Canceled notes out. */
    @Query("select i.product.code, max(i.product.description), max(i.product.ncm), coalesce(sum(i.totalAmount), 0), count(distinct d.id)"
            + " from FiscalDocumentItem i join i.document d"
            + " where d.company.id = :companyId and d.deleted = false"
            + " and d.direction = br.com.cshub.taxify.enumerated.FiscalDirection.INBOUND"
            + " and d.status <> " + CANCELED
            + " and d.issueDate >= :from and d.issueDate < :to"
            + " group by i.product.code order by coalesce(sum(i.totalAmount), 0) desc")
    List<Object[]> sumPurchasesByProduct(@Param("companyId") UUID companyId, @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);
}
