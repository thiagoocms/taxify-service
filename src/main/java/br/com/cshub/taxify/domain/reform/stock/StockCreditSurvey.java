package br.com.cshub.taxify.domain.reform.stock;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.StockSurveyClassification;
import br.com.cshub.taxify.enumerated.StockSurveyContactStatus;
import br.com.cshub.taxify.enumerated.StockSurveyCoverage;
import br.com.cshub.taxify.enumerated.StockSurveyRegimeSource;
import br.com.cshub.taxify.enumerated.TaxRegime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The stock credit survey of one company and year (spec 2026-11-27, F5a). Two owners, never mixed:
 * <ul>
 *   <li>the calculation columns - written only by the routine and "refresh now", with an idempotent upsert
 *       (CE-RN-08 b, StockSurveySourceRepository), never through this entity;</li>
 *   <li>the contact status, the note and {@code version} - changed only by {@link #changeContactStatus} (CE-RN-10).</li>
 * </ul>
 * {@code @DynamicUpdate}: a status change writes only its own columns. {@code calculatedAt} null = the row was only
 * reserved by the refresh cooldown and has no survey yet.
 */
@Entity
@DynamicUpdate
@Table(name = "stock_credit_surveys")
public class StockCreditSurvey extends AbstractAuditingEntity {

    public static final int NOTE_MAX = 500;

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "company_id", length = 36, nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "survey_year", nullable = false, updatable = false)
    private Integer surveyYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_regime", length = 30, insertable = false, updatable = false)
    private TaxRegime referenceRegime;

    @Column(name = "reference_regime_since", insertable = false, updatable = false)
    private LocalDate referenceRegimeSince;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_regime_source", length = 12, insertable = false, updatable = false)
    private StockSurveyRegimeSource referenceRegimeSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_year_regime", length = 30, insertable = false, updatable = false)
    private TaxRegime nextYearRegime;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", length = 20, insertable = false, updatable = false)
    private StockSurveyClassification classification;

    @Column(name = "purchases_total", precision = 19, scale = 2, insertable = false, updatable = false)
    private BigDecimal purchasesTotal;

    @Column(name = "purchase_items", insertable = false, updatable = false)
    private Integer purchaseItems;

    @Column(name = "inbound_notes", insertable = false, updatable = false)
    private Integer inboundNotes;

    @Column(name = "months_with_inbound", insertable = false, updatable = false)
    private Integer monthsWithInbound;

    @Column(name = "months_evaluated", insertable = false, updatable = false)
    private Integer monthsEvaluated;

    @Enumerated(EnumType.STRING)
    @Column(name = "coverage", length = 15, insertable = false, updatable = false)
    private StockSurveyCoverage coverage;

    @Column(name = "first_inbound", insertable = false, updatable = false)
    private LocalDate firstInbound;

    @Column(name = "last_inbound", insertable = false, updatable = false)
    private LocalDate lastInbound;

    @Column(name = "purchases_by_month_json", columnDefinition = "text", insertable = false, updatable = false)
    private String purchasesByMonthJson;

    @Column(name = "purchases_by_cst_json", columnDefinition = "text", insertable = false, updatable = false)
    private String purchasesByCstJson;

    @Column(name = "top_ncm_json", columnDefinition = "text", insertable = false, updatable = false)
    private String topNcmJson;

    @Column(name = "calculated_at", insertable = false, updatable = false)
    private LocalDateTime calculatedAt;

    @Column(name = "refresh_requested_at", insertable = false, updatable = false)
    private LocalDateTime refreshRequestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_status", length = 25, nullable = false)
    private StockSurveyContactStatus contactStatus;

    @Column(name = "status_changed_by", length = 50)
    private String statusChangedBy;

    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

    @Column(name = "note", length = NOTE_MAX)
    private String note;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    protected StockCreditSurvey() {
    }

    /** A survey row has been calculated at least once (CE-RN-10: no status change before that). */
    public boolean isCalculated() {
        return calculatedAt != null;
    }

    public boolean hasVersion(Integer expected) {
        return Objects.equals(version, expected);
    }

    /**
     * CE-RN-10: any status may go to any other; the note (already trimmed, blank = null) replaces the previous one.
     *
     * @return the history event of the change
     */
    public StockCreditSurveyEvent changeContactStatus(StockSurveyContactStatus to, String newNote, String login,
                                                      LocalDateTime at) {
        StockCreditSurveyEvent event = StockCreditSurveyEvent.of(id, contactStatus, to, newNote, login, at);
        this.contactStatus = to;
        this.note = newNote;
        this.statusChangedBy = login;
        this.statusChangedAt = at;
        return event;
    }

    /** The rule of CE-RN-03 that the stored regimes match (its reason text). */
    public StockSurveyRule rule() {
        return StockSurveyRule.of(referenceRegime, nextYearRegime);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public Integer getSurveyYear() {
        return surveyYear;
    }

    public TaxRegime getReferenceRegime() {
        return referenceRegime;
    }

    public LocalDate getReferenceRegimeSince() {
        return referenceRegimeSince;
    }

    public StockSurveyRegimeSource getReferenceRegimeSource() {
        return referenceRegimeSource;
    }

    public TaxRegime getNextYearRegime() {
        return nextYearRegime;
    }

    public StockSurveyClassification getClassification() {
        return classification;
    }

    public BigDecimal getPurchasesTotal() {
        return purchasesTotal;
    }

    public Integer getPurchaseItems() {
        return purchaseItems;
    }

    public Integer getInboundNotes() {
        return inboundNotes;
    }

    public Integer getMonthsWithInbound() {
        return monthsWithInbound;
    }

    public Integer getMonthsEvaluated() {
        return monthsEvaluated;
    }

    public StockSurveyCoverage getCoverage() {
        return coverage;
    }

    public LocalDate getFirstInbound() {
        return firstInbound;
    }

    public LocalDate getLastInbound() {
        return lastInbound;
    }

    public String getPurchasesByMonthJson() {
        return purchasesByMonthJson;
    }

    public String getPurchasesByCstJson() {
        return purchasesByCstJson;
    }

    public String getTopNcmJson() {
        return topNcmJson;
    }

    public LocalDateTime getCalculatedAt() {
        return calculatedAt;
    }

    public LocalDateTime getRefreshRequestedAt() {
        return refreshRequestedAt;
    }

    public StockSurveyContactStatus getContactStatus() {
        return contactStatus;
    }

    public String getStatusChangedBy() {
        return statusChangedBy;
    }

    public LocalDateTime getStatusChangedAt() {
        return statusChangedAt;
    }

    public String getNote() {
        return note;
    }

    public Integer getVersion() {
        return version;
    }
}
