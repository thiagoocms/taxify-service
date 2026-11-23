package br.com.cshub.taxify.domain.audit;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/** The last Auditoria tipo malha run of one company and competence. */
@Entity
@Table(name = "audit_runs",
        uniqueConstraints = @UniqueConstraint(name = "uk_audit_runs_company_competence", columnNames = {"company_id", "competence"}))
public class AuditRun extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "competence", length = 7, nullable = false)
    private String competence;

    @Column(name = "ran_at", nullable = false)
    private LocalDateTime ranAt;

    @Column(name = "ran_by", length = 50, nullable = false)
    private String ranBy;

    @Column(name = "findings", nullable = false)
    private int findings;

    @Column(name = "notes_checked", nullable = false)
    private int notesChecked;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getCompetence() { return competence; }
    public void setCompetence(String competence) { this.competence = competence; }
    public LocalDateTime getRanAt() { return ranAt; }
    public void setRanAt(LocalDateTime ranAt) { this.ranAt = ranAt; }
    public String getRanBy() { return ranBy; }
    public void setRanBy(String ranBy) { this.ranBy = ranBy; }
    public int getFindings() { return findings; }
    public void setFindings(int findings) { this.findings = findings; }
    public int getNotesChecked() { return notesChecked; }
    public void setNotesChecked(int notesChecked) { this.notesChecked = notesChecked; }
}
