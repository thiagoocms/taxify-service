package br.com.cshub.taxify.repository;

import br.com.cshub.taxify.common.AbstractJpaRepository;
import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.domain.fiscaldocument.ProviderKey;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import org.springframework.data.jpa.repository.Modifying;
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
     * A row the counts created, as one atomic upsert on the unique key (company, channel, type, side, month): the
     * scheduler and a manual sync (or two instances) creating the same month never collide (I-3) - the later write wins,
     * as for a row both update; {@code closed_at}: the first close wins. No {@code AS new} syntax (MySQL 5.7).
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO capture_period_counts (id, company_id, channel, document_type, side, period_month, window_start,"
            + " window_end, provider_total, provider_counted_at, imported_total, stored_any_source_total, known_gap,"
            + " imported_counted_at, closed_at, created_by, created_date, deleted)"
            + " VALUES (:id, :companyId, :channel, :documentType, :side, :month, :windowStart, :windowEnd, :providerTotal,"
            + " :providerCountedAt, :importedTotal, :storedAnySource, :knownGap, :importedCountedAt, :closedAt, :by, :now, 0)"
            + " ON DUPLICATE KEY UPDATE window_start = :windowStart, window_end = :windowEnd, provider_total = :providerTotal,"
            + " provider_counted_at = :providerCountedAt, imported_total = :importedTotal,"
            + " stored_any_source_total = :storedAnySource, known_gap = :knownGap, imported_counted_at = :importedCountedAt,"
            + " closed_at = COALESCE(closed_at, :closedAt), last_modified_by = :by, last_modified_date = :now",
            nativeQuery = true)
    int upsert(@Param("id") String id, @Param("companyId") String companyId, @Param("channel") String channel,
               @Param("documentType") String documentType, @Param("side") String side, @Param("month") String month,
               @Param("windowStart") java.time.LocalDate windowStart, @Param("windowEnd") java.time.LocalDate windowEnd,
               @Param("providerTotal") Integer providerTotal, @Param("providerCountedAt") java.time.LocalDateTime providerCountedAt,
               @Param("importedTotal") int importedTotal, @Param("storedAnySource") Integer storedAnySource,
               @Param("knownGap") Integer knownGap, @Param("importedCountedAt") java.time.LocalDateTime importedCountedAt,
               @Param("closedAt") java.time.LocalDateTime closedAt, @Param("by") String by,
               @Param("now") java.time.LocalDateTime now);

    /**
     * When each side of each company was last checked, any month: rows {company id, side, last check} - the queue
     * order of the scheduler (the old {@code ultima_verificacao_em}/{@code issued_checked_at}).
     */
    @Query("select c.company.id, c.side, max(c.importedCountedAt) from CapturePeriodCount c where c.channel = :channel"
            + " and c.documentType = :documentType and c.importedCountedAt is not null group by c.company.id, c.side")
    List<Object[]> lastCheckedBySide(@Param("channel") ProviderKey channel,
                                     @Param("documentType") FiscalDocumentType documentType);
}
