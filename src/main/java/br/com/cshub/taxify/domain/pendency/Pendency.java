package br.com.cshub.taxify.domain.pendency;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyStatus;
import br.com.cshub.taxify.enumerated.PendencyType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Something missing in one company (roadmap M13): an expiring certificate, a capture failing, a
 * note close to its manifestation deadline... One row per cause ({@link #naturalKey}) and company.
 * Closing (resolved or dismissed) needs a comment; the generator closes it by itself when the cause
 * goes away ({@link #autoClosed}) and reopens it if the cause comes back.
 */
@Entity
@Table(name = "pendencies",
        uniqueConstraints = @UniqueConstraint(name = "uk_pendencies_company_key", columnNames = {"company_id", "natural_key"}),
        indexes = @Index(name = "idx_pendencies_status_severity", columnList = "status, severity"))
public class Pendency extends AbstractAuditingEntity {

    /** What {@link #objectId} points to - the screen links to it. */
    public static final String OBJECT_COMPANY = "COMPANY";
    public static final String OBJECT_NOTE = "NOTE";
    public static final String OBJECT_NOTE_KEY = "NOTE_KEY";
    /** A service invoice (NFS-e) - the screen opens the service notes. */
    public static final String OBJECT_SERVICE_NOTE = "SERVICE_NOTE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", length = 40, nullable = false)
    private PendencyType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "severity", length = 10, nullable = false)
    private PendencySeverity severity;

    /** The cause: type + object ("CERTIFICATE_EXPIRING:id", "MANIFESTATION_DEADLINE:key"...). */
    @Column(name = "natural_key", length = 150, nullable = false)
    private String naturalKey;

    /** "2026-11" - the month it belongs to, when it belongs to one (a note's issue). */
    @Column(name = "competence", length = 7)
    private String competence;

    @Column(name = "object_type", length = 20)
    private String objectType;

    @Column(name = "object_id", length = 64)
    private String objectId;

    @Column(name = "title", length = 200, nullable = false)
    private String title;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 15, nullable = false)
    private PendencyStatus status = PendencyStatus.ABERTA;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** The cause wasn't found since then - a closed pendency whose cause comes back is reopened. */
    @Column(name = "absent_since")
    private LocalDateTime absentSince;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 50)
    private String closedBy;

    @Column(name = "close_comment", length = 500)
    private String closeComment;

    /** Closed by the generator because the cause went away, not by a person. */
    @Column(name = "auto_closed", nullable = false)
    private boolean autoClosed;

    public boolean isOpen() {
        return status == PendencyStatus.ABERTA;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public PendencyType getType() { return type; }
    public void setType(PendencyType type) { this.type = type; }
    public PendencySeverity getSeverity() { return severity; }
    public void setSeverity(PendencySeverity severity) { this.severity = severity; }
    public String getNaturalKey() { return naturalKey; }
    public void setNaturalKey(String naturalKey) { this.naturalKey = naturalKey; }
    public String getCompetence() { return competence; }
    public void setCompetence(String competence) { this.competence = competence; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public PendencyStatus getStatus() { return status; }
    public void setStatus(PendencyStatus status) { this.status = status; }
    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public LocalDateTime getAbsentSince() { return absentSince; }
    public void setAbsentSince(LocalDateTime absentSince) { this.absentSince = absentSince; }
    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String closedBy) { this.closedBy = closedBy; }
    public String getCloseComment() { return closeComment; }
    public void setCloseComment(String closeComment) { this.closeComment = closeComment; }
    public boolean isAutoClosed() { return autoClosed; }
    public void setAutoClosed(boolean autoClosed) { this.autoClosed = autoClosed; }
}
