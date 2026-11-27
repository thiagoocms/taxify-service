package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * What a provider counted and what was imported for one company, channel, document type, side and month (FD-RN-32,
 * T2) - one row per period instead of the fixed counter columns of {@code fiscal_xml_sync_control}. Upserted by the
 * unique key, safe with two instances.
 */
@Entity
@Table(name = "capture_period_counts",
        uniqueConstraints = @UniqueConstraint(name = "uk_capture_period_counts",
                columnNames = {"company_id", "channel", "document_type", "side", "period_month"}),
        indexes = @Index(name = "idx_cpc_channel_period", columnList = "channel, document_type, period_month"))
public class CapturePeriodCount extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, foreignKey = @ForeignKey(name = "fk_capture_period_counts_company"))
    private Company company;

    /** The provider key - an open value (FD-RN-40). */
    @Convert(converter = ProviderKeyConverter.class)
    @Column(name = "channel", length = ProviderKey.MAX_LENGTH, nullable = false, updatable = false)
    private ProviderKey channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "document_type", length = 10, nullable = false, updatable = false)
    private FiscalDocumentType documentType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "side", length = 10, nullable = false, updatable = false)
    private CaptureSide side;

    /** {@code yyyy-MM} of Recife. */
    @Convert(converter = YearMonthConverter.class)
    @Column(name = "period_month", length = 7, columnDefinition = "char(7)", nullable = false, updatable = false)
    private YearMonth periodMonth;

    @Column(name = "window_start")
    private LocalDate windowStart;

    @Column(name = "window_end")
    private LocalDate windowEnd;

    /** What the provider counted; null = never counted. */
    @Column(name = "provider_total")
    private Integer providerTotal;

    @Column(name = "provider_counted_at")
    private LocalDateTime providerCountedAt;

    /** What this provider delivered of the period (the {@code Skip}). */
    @Column(name = "imported_total", nullable = false)
    private int importedTotal;

    /** What the Taxify has of the period by any origin. */
    @Column(name = "stored_any_source_total")
    private Integer storedAnySourceTotal;

    /** What the provider counts but never delivers. */
    @Column(name = "known_gap")
    private Integer knownGap;

    @Column(name = "imported_counted_at")
    private LocalDateTime importedCountedAt;

    /** The period was closed (the month turned). */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    protected CapturePeriodCount() {
    }

    public CapturePeriodCount(Company company, ProviderKey channel, FiscalDocumentType documentType, CaptureSide side,
                              YearMonth periodMonth) {
        this.company = Objects.requireNonNull(company, "company");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.side = Objects.requireNonNull(side, "side");
        this.periodMonth = Objects.requireNonNull(periodMonth, "periodMonth");
    }

    /** The window asked to the provider; start not after end. */
    public void askedWindow(LocalDate start, LocalDate end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("window start after end");
        }
        this.windowStart = start;
        this.windowEnd = end;
    }

    /** What the provider counted for the period at {@code at}; a count is never negative. */
    public void recordProviderCount(Integer total, LocalDateTime at) {
        this.providerTotal = nonNegativeOrNull(total);
        this.providerCountedAt = at;
    }

    /** What this provider delivered of the period, counted at {@code at}. */
    public void recordImported(int total, LocalDateTime at) {
        this.importedTotal = nonNegative(total);
        this.importedCountedAt = at;
    }

    public void recordStoredAnySource(Integer total) {
        this.storedAnySourceTotal = nonNegativeOrNull(total);
    }

    public void recordKnownGap(Integer gap) {
        this.knownGap = nonNegativeOrNull(gap);
    }

    /** Closes the period; the first close wins. */
    public boolean close(LocalDateTime at) {
        Objects.requireNonNull(at, "at");
        if (closedAt != null) {
            return false;
        }
        this.closedAt = at;
        return true;
    }

    public boolean isClosed() {
        return closedAt != null;
    }

    /**
     * What is still missing from the provider: {@code provider_total - known_gap - imported_total}, never below zero;
     * null while the provider never counted (spec 3.11 - the gap the provider counts but never delivers is not
     * pending).
     */
    public Integer missing() {
        if (providerTotal == null) {
            return null;
        }
        int gap = knownGap == null ? 0 : knownGap;
        return Math.max(0, providerTotal - gap - importedTotal);
    }

    private static int nonNegative(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        return value;
    }

    private static Integer nonNegativeOrNull(Integer value) {
        return value == null ? null : nonNegative(value);
    }

    public UUID getId() {
        return id;
    }

    public Company getCompany() {
        return company;
    }

    public ProviderKey getChannel() {
        return channel;
    }

    public FiscalDocumentType getDocumentType() {
        return documentType;
    }

    public CaptureSide getSide() {
        return side;
    }

    public YearMonth getPeriodMonth() {
        return periodMonth;
    }

    public LocalDate getWindowStart() {
        return windowStart;
    }

    public LocalDate getWindowEnd() {
        return windowEnd;
    }

    public Integer getProviderTotal() {
        return providerTotal;
    }

    public LocalDateTime getProviderCountedAt() {
        return providerCountedAt;
    }

    public int getImportedTotal() {
        return importedTotal;
    }

    public Integer getStoredAnySourceTotal() {
        return storedAnySourceTotal;
    }

    public Integer getKnownGap() {
        return knownGap;
    }

    public LocalDateTime getImportedCountedAt() {
        return importedCountedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }
}
