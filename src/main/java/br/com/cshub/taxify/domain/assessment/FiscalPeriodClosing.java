package br.com.cshub.taxify.domain.assessment;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.TaxRegime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fechamento fiscal of one company's month. Closing freezes the assessment as it was
 * ({@link #snapshot}, JSON of TaxAssessmentDTO) - notes arriving later no longer change a
 * closed month until it is reopened. The guide (DAS/DARF) is tracked on it too.
 */
@Entity
@Table(name = "fiscal_period_closings",
        uniqueConstraints = @UniqueConstraint(name = "uk_fiscal_period_closing", columnNames = {"company_id", "period"}))
public class FiscalPeriodClosing extends AbstractAuditingEntity {

    public enum Status {
        OPEN,
        CLOSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** First day of the month. */
    @Column(name = "period", nullable = false)
    private LocalDate period;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 10, nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    /** The assessment at closing time (TaxAssessmentDTO as JSON). */
    @Column(name = "snapshot", columnDefinition = "TEXT")
    private String snapshot;

    // Frozen headline values - for listing/reporting without parsing the snapshot.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tax_regime", length = 30)
    private TaxRegime taxRegime;

    @Column(name = "net_revenue", precision = 19, scale = 2)
    private BigDecimal netRevenue;

    @Column(name = "rbt12", precision = 19, scale = 2)
    private BigDecimal rbt12;

    @Column(name = "total_taxes", precision = 19, scale = 2)
    private BigDecimal totalTaxes;

    @Column(name = "guide_issued", nullable = false)
    private boolean guideIssued;

    @Column(name = "guide_amount", precision = 19, scale = 2)
    private BigDecimal guideAmount;

    @Column(name = "guide_due_date")
    private LocalDate guideDueDate;

    @Column(name = "notes", length = 1000)
    private String notes;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public LocalDate getPeriod() {
        return period;
    }

    public void setPeriod(LocalDate period) {
        this.period = period;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isClosed() {
        return status == Status.CLOSED;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public TaxRegime getTaxRegime() {
        return taxRegime;
    }

    public BigDecimal getNetRevenue() {
        return netRevenue;
    }

    public BigDecimal getRbt12() {
        return rbt12;
    }

    public BigDecimal getTotalTaxes() {
        return totalTaxes;
    }

    public boolean isGuideIssued() {
        return guideIssued;
    }

    public BigDecimal getGuideAmount() {
        return guideAmount;
    }

    public LocalDate getGuideDueDate() {
        return guideDueDate;
    }

    public String getNotes() {
        return notes;
    }

    public void close(String by, LocalDateTime at, String snapshot, TaxRegime regime, BigDecimal netRevenue,
                      BigDecimal rbt12, BigDecimal totalTaxes) {
        this.status = Status.CLOSED;
        this.closedBy = by;
        this.closedAt = at;
        this.snapshot = snapshot;
        this.taxRegime = regime;
        this.netRevenue = netRevenue;
        this.rbt12 = rbt12;
        this.totalTaxes = totalTaxes;
    }

    /** Back to open: the month is recalculated from the notes again; the guide data stays. */
    public void reopen() {
        this.status = Status.OPEN;
        this.closedAt = null;
        this.closedBy = null;
        this.snapshot = null;
    }

    public void updateGuide(boolean issued, BigDecimal amount, LocalDate dueDate, String notes) {
        this.guideIssued = issued;
        this.guideAmount = amount;
        this.guideDueDate = dueDate;
        this.notes = notes == null || notes.isBlank() ? null : notes.strip();
    }
}
