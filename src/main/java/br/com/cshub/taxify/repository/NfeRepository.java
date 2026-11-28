package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscaldocument.Nfe;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** NF-e model 55 only ({@code from Nfe}): the queries that also filtered the model today - the manifestação (FD-RN-14). */
@Repository
public interface NfeRepository extends AbstractJpaRepository<Nfe, UUID> {

    /**
     * Entries the company still owes a conclusive manifestation for: NF-e (model 55) addressed to its CNPJ, not
     * canceled, issued since {@code since}, without a registered confirmation, unknown or not-performed event.
     */
    @Query("select d from Nfe d where d.company.id = :companyId and d.deleted = false"
            + " and d.companyRole <> br.com.cshub.taxify.enumerated.CompanyDocumentRole.ISSUER"
            + " and d.recipient.documentType = br.com.cshub.taxify.enumerated.TaxpayerDocumentType.CNPJ"
            + " and d.recipient.document = :cnpj"
            + " and d.status <> br.com.cshub.taxify.enumerated.FiscalDocumentStatus.CANCELED"
            + " and d.issueDate >= :since and d.accessKey is not null"
            + " and not exists (select e.id from FiscalDocumentEvent e where e.company.id = :companyId"
            + "      and e.accessKey = d.accessKey and e.registered = true and e.eventType in :conclusive)"
            + " order by d.issueDate asc")
    List<Nfe> findAwaitingManifestation(@Param("companyId") UUID companyId, @Param("cnpj") String cnpj,
                                        @Param("since") LocalDateTime since,
                                        @Param("conclusive") Collection<String> conclusive, Pageable pageable);
}
