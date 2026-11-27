package br.com.cshub.taxify.domain.taxclearance;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.TaxClearanceStatus;
import br.com.cshub.taxify.enumerated.TaxClearanceType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Certidão SEFAZ-PE (M5): one state certidão of a company, as read from its PDF. The newest one (by validity) is the
 * one that counts; the older ones stay as history. The PDF is kept in the XMLs' storage, as the Fronteira extract.
 */
@Entity
@Table(name = "certidoes_estaduais",
        indexes = @Index(name = "idx_certidoes_estaduais_company_validade", columnList = "company_id, valida_ate"))
public class StateTaxClearance extends AbstractAuditingEntity {

    /** The PDF sent by a user (issued by them on e-Fisco). */
    public static final String ORIGIN_UPLOAD = "UPLOAD";
    /** Issued by Taxify on e-Fisco's public page. */
    public static final String ORIGIN_EFISCO = "EFISCO";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "uf", length = 2, nullable = false)
    private String uf;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tipo", length = 10, nullable = false)
    private TaxClearanceType type;

    @Column(name = "numero", length = 60)
    private String number;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "situacao", length = 30, nullable = false)
    private TaxClearanceStatus status;

    @Column(name = "emitida_em")
    private LocalDate issuedOn;

    @Column(name = "valida_ate")
    private LocalDate validUntil;

    /** The CNPJ (digits) the certidão was issued for. */
    @Column(name = "documento", length = 20)
    private String document;

    @Column(name = "pdf_path", length = 500)
    private String pdfPath;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "origem", length = 20, nullable = false)
    private String origin;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @Column(name = "imported_by", length = 50, nullable = false)
    private String importedBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getUf() { return uf; }
    public void setUf(String uf) { this.uf = uf; }
    public TaxClearanceType getType() { return type; }
    public void setType(TaxClearanceType type) { this.type = type; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public TaxClearanceStatus getStatus() { return status; }
    public void setStatus(TaxClearanceStatus status) { this.status = status; }
    public LocalDate getIssuedOn() { return issuedOn; }
    public void setIssuedOn(LocalDate issuedOn) { this.issuedOn = issuedOn; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public String getDocument() { return document; }
    public void setDocument(String document) { this.document = document; }
    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }
    public String getImportedBy() { return importedBy; }
    public void setImportedBy(String importedBy) { this.importedBy = importedBy; }
}
