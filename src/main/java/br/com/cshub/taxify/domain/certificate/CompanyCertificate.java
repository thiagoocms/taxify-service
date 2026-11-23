package br.com.cshub.taxify.domain.certificate;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A company's A1 certificate (e-CNPJ). The .pfx sits in the XML storage (S3 or disk),
 * encrypted by {@link br.com.cshub.taxify.common.SecretCipher}; the password is kept here,
 * encrypted the same way. One per company - a new upload replaces it. A branch with no
 * certificate of its own uses one of the same CNPJ root (the matriz's), as SEFAZ allows.
 */
@Entity
@Table(name = "company_certificates", uniqueConstraints = @UniqueConstraint(columnNames = "company_id"))
public class CompanyCertificate extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "password_encrypted", nullable = false, length = 500)
    private String passwordEncrypted;

    /** CNPJ found in the certificate's subject - only digits; null when it couldn't be read. */
    @Column(name = "cnpj", length = 14)
    private String cnpj;

    @Column(name = "subject_name", length = 300)
    private String subjectName;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until", nullable = false)
    private LocalDateTime validUntil;

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

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getPasswordEncrypted() {
        return passwordEncrypted;
    }

    public void setPasswordEncrypted(String passwordEncrypted) {
        this.passwordEncrypted = passwordEncrypted;
    }

    public String getCnpj() {
        return cnpj;
    }

    public void setCnpj(String cnpj) {
        this.cnpj = cnpj;
    }

    public String getSubjectName() {
        return subjectName;
    }

    public void setSubjectName(String subjectName) {
        this.subjectName = subjectName;
    }

    public LocalDateTime getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDateTime validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDateTime getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDateTime validUntil) {
        this.validUntil = validUntil;
    }
}
