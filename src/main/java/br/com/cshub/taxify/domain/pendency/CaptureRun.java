package br.com.cshub.taxify.domain.pendency;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/** One collector run for one company (roadmap's coleta_execucao) - the integrations' health on the pendencies screen. */
@Entity
@Table(name = "capture_runs", indexes = @Index(name = "idx_capture_runs_company_started", columnList = "company_id, started_at"))
public class CaptureRun extends AbstractAuditingEntity {

    public static final String SEFAZ_DIST = "SEFAZ_DIST";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "service", length = 20, nullable = false)
    private String service;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** OK, SEM_MOVIMENTO or ERRO. */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "new_documents")
    private Integer newDocuments;

    @Column(name = "detail", length = 500)
    private String detail;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getNewDocuments() { return newDocuments; }
    public void setNewDocuments(Integer newDocuments) { this.newDocuments = newDocuments; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
