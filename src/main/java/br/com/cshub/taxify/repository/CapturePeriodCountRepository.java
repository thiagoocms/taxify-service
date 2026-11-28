package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.domain.fiscaldocument.ProviderKey;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** The capture counts per company, channel, document type, side and month (FD-RN-32, spec 3.11). */
@Repository
public interface CapturePeriodCountRepository extends AbstractJpaRepository<CapturePeriodCount, UUID> {

    /** Every month of the company for the channel and type - a handful of rows (two sides x the months captured). */
    @Query("select c from CapturePeriodCount c where c.company.id = :companyId and c.channel = :channel"
            + " and c.documentType = :documentType")
    List<CapturePeriodCount> findOfCompany(@Param("companyId") UUID companyId, @Param("channel") ProviderKey channel,
                                           @Param("documentType") FiscalDocumentType documentType);

    /** The same for several companies at once (the capture panel, one query per page). */
    @Query("select c from CapturePeriodCount c where c.company.id in :companyIds and c.channel = :channel"
            + " and c.documentType = :documentType")
    List<CapturePeriodCount> findOfCompanies(@Param("companyIds") java.util.Collection<UUID> companyIds,
                                             @Param("channel") ProviderKey channel,
                                             @Param("documentType") FiscalDocumentType documentType);

    /**
     * When each side of each company was last checked, any month: rows {company id, side, last check} - the queue
     * order of the scheduler (the old {@code ultima_verificacao_em}/{@code issued_checked_at}).
     */
    @Query("select c.company.id, c.side, max(c.importedCountedAt) from CapturePeriodCount c where c.channel = :channel"
            + " and c.documentType = :documentType and c.importedCountedAt is not null group by c.company.id, c.side")
    List<Object[]> lastCheckedBySide(@Param("channel") ProviderKey channel,
                                     @Param("documentType") FiscalDocumentType documentType);
}
