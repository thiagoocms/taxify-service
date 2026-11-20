package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import org.springframework.data.domain.Pageable;
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
}
