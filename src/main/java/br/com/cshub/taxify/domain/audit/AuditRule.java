package br.com.cshub.taxify.domain.audit;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

/** One Auditoria tipo malha rule as the firm keeps it: legal basis, validity, on/off and who checked it. */
@Entity
@Table(name = "audit_rules", uniqueConstraints = @UniqueConstraint(name = "uk_audit_rules_code", columnNames = "code"))
public class AuditRule extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", length = 40, nullable = false)
    private String code;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "rule_group", length = 20, nullable = false)
    private String group;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "severity", length = 10, nullable = false)
    private PendencySeverity severity;

    @Column(name = "legal_basis", length = 500)
    private String legalBasis;

    @Column(name = "link", length = 300)
    private String link;

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Who in the fiscal team checked the rule - until then its findings are "a conferir". */
    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    public boolean isReviewed() {
        return reviewedAt != null;
    }

    /** On and valid on some day of the month. */
    public boolean appliesTo(YearMonth month) {
        return enabled && (validFrom == null || !validFrom.isAfter(month.atEndOfMonth()))
                && (validUntil == null || !validUntil.isBefore(month.atDay(1)));
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public PendencySeverity getSeverity() { return severity; }
    public void setSeverity(PendencySeverity severity) { this.severity = severity; }
    public String getLegalBasis() { return legalBasis; }
    public void setLegalBasis(String legalBasis) { this.legalBasis = legalBasis; }
    public String getLink() { return link; }
    public void setLink(String link) { this.link = link; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
