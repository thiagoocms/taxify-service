package br.com.cshub.taxify.domain.xml;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A note SEFAZ handed over only as a summary (resNFe): the company is the recipient and no
 * "Ciência da Operação" was registered yet, so the full XML isn't released. Kept apart from
 * fiscal_xml_documents - a summary has no items, so it must not reach the checks, the
 * rectification or the assessment. Completed ({@link #completedAt}) once the full note arrives
 * from any source.
 */
@Entity
@Table(name = "sefaz_note_summaries",
        uniqueConstraints = @UniqueConstraint(name = "uk_sefaz_note_summaries_company_key", columnNames = {"company_id", "access_key"}))
public class SefazNoteSummary extends AbstractAuditingEntity {

    /** The Ciência da Operação sent by Taxify for this note. */
    public enum CienciaStatus {
        /** Not sent yet (or auto-ciência is off). */
        PENDENTE,
        /** Registered at SEFAZ (also when it already had one - cStat 573). */
        ENVIADA,
        /** SEFAZ refused it - see the detail; retried a few times, then left for a person. */
        ERRO
    }

    /** cSitNFe 1 = authorized - the only situation a ciência makes sense for. */
    public static final String AUTHORIZED = "1";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "access_key", length = 44, nullable = false)
    private String accessKey;

    @Column(name = "nsu", length = 15)
    private String nsu;

    @Column(name = "issuer_document", length = 14)
    private String issuerDocument;

    @Column(name = "issuer_name", length = 150)
    private String issuerName;

    @Column(name = "issuer_ie", length = 20)
    private String issuerIe;

    @Column(name = "issue_date")
    private LocalDateTime issueDate;

    /** tpNF: 0 entrada, 1 saída - from the issuer's point of view. */
    @Column(name = "operation_type", length = 1)
    private String operationType;

    @Column(name = "total_value", precision = 15, scale = 2)
    private BigDecimal totalValue;

    /** cSitNFe: 1 autorizada, 2 denegada, 3 cancelada. */
    @Column(name = "situation", length = 1)
    private String situation;

    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;

    @Column(name = "protocol", length = 20)
    private String protocol;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "ciencia_status", length = 20, nullable = false)
    private CienciaStatus cienciaStatus = CienciaStatus.PENDENTE;

    @Column(name = "ciencia_at")
    private LocalDateTime cienciaAt;

    @Column(name = "ciencia_protocol", length = 20)
    private String cienciaProtocol;

    @Column(name = "ciencia_detail", length = 255)
    private String cienciaDetail;

    @Column(name = "ciencia_attempts", nullable = false)
    private int cienciaAttempts;

    /** Last time the full note was asked by key (consChNFe) after the ciência. */
    @Column(name = "last_fetch_at")
    private LocalDateTime lastFetchAt;

    @Column(name = "fetch_detail", length = 255)
    private String fetchDetail;

    /** The full note arrived (SEFAZ, SIEG or upload) - nothing left to do for this summary. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public boolean isCompleted() {
        return completedAt != null;
    }

    public boolean isAuthorized() {
        return situation == null || AUTHORIZED.equals(situation);
    }

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

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getNsu() {
        return nsu;
    }

    public void setNsu(String nsu) {
        this.nsu = nsu;
    }

    public String getIssuerDocument() {
        return issuerDocument;
    }

    public void setIssuerDocument(String issuerDocument) {
        this.issuerDocument = issuerDocument;
    }

    public String getIssuerName() {
        return issuerName;
    }

    public void setIssuerName(String issuerName) {
        this.issuerName = issuerName;
    }

    public String getIssuerIe() {
        return issuerIe;
    }

    public void setIssuerIe(String issuerIe) {
        this.issuerIe = issuerIe;
    }

    public LocalDateTime getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(LocalDateTime issueDate) {
        this.issueDate = issueDate;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }

    public String getSituation() {
        return situation;
    }

    public void setSituation(String situation) {
        this.situation = situation;
    }

    public LocalDateTime getAuthorizedAt() {
        return authorizedAt;
    }

    public void setAuthorizedAt(LocalDateTime authorizedAt) {
        this.authorizedAt = authorizedAt;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public CienciaStatus getCienciaStatus() {
        return cienciaStatus;
    }

    public void setCienciaStatus(CienciaStatus cienciaStatus) {
        this.cienciaStatus = cienciaStatus;
    }

    public LocalDateTime getCienciaAt() {
        return cienciaAt;
    }

    public void setCienciaAt(LocalDateTime cienciaAt) {
        this.cienciaAt = cienciaAt;
    }

    public String getCienciaProtocol() {
        return cienciaProtocol;
    }

    public void setCienciaProtocol(String cienciaProtocol) {
        this.cienciaProtocol = cienciaProtocol;
    }

    public String getCienciaDetail() {
        return cienciaDetail;
    }

    public void setCienciaDetail(String cienciaDetail) {
        this.cienciaDetail = cienciaDetail;
    }

    public int getCienciaAttempts() {
        return cienciaAttempts;
    }

    public void setCienciaAttempts(int cienciaAttempts) {
        this.cienciaAttempts = cienciaAttempts;
    }

    public LocalDateTime getLastFetchAt() {
        return lastFetchAt;
    }

    public void setLastFetchAt(LocalDateTime lastFetchAt) {
        this.lastFetchAt = lastFetchAt;
    }

    public String getFetchDetail() {
        return fetchDetail;
    }

    public void setFetchDetail(String fetchDetail) {
        this.fetchDetail = fetchDetail;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
