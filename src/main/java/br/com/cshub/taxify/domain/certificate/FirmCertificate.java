package br.com.cshub.taxify.domain.certificate;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The accountant's A1 certificate (e-CPF) of a firm - what logs into PE's e-Fisco for the companies of the
 * portfolio to fetch the Fronteira extract. Stored like {@link CompanyCertificate}: the .pfx in the storage and
 * the password here, both encrypted when CERT_ENCRYPTION_KEY is set. One per firm - a new upload replaces it.
 * Also keeps how the last e-Fisco sync went.
 */
@Entity
@Table(name = "firm_certificates", uniqueConstraints = @UniqueConstraint(columnNames = "accounting_firm_id"))
public class FirmCertificate extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_firm_id", nullable = false)
    private AccountingFirm accountingFirm;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "password_encrypted", nullable = false, length = 500)
    private String passwordEncrypted;

    /** The holder's CPF from the subject (e-CPF: "NOME:12345678901") - only digits. */
    @Column(name = "cpf", length = 11)
    private String cpf;

    @Column(name = "subject_name", length = 300)
    private String subjectName;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_until", nullable = false)
    private LocalDateTime validUntil;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    /** OK, PARCIAL, ERRO, BLOQUEADO (a Cloudflare challenge or login refused) - see EfiscoSyncService. */
    @Column(name = "last_sync_status", length = 30)
    private String lastSyncStatus;

    @Column(name = "last_sync_detail", length = 1000)
    private String lastSyncDetail;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public AccountingFirm getAccountingFirm() { return accountingFirm; }
    public void setAccountingFirm(AccountingFirm accountingFirm) { this.accountingFirm = accountingFirm; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getPasswordEncrypted() { return passwordEncrypted; }
    public void setPasswordEncrypted(String passwordEncrypted) { this.passwordEncrypted = passwordEncrypted; }
    public String getCpf() { return cpf; }
    public void setCpf(String cpf) { this.cpf = cpf; }
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    public LocalDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDateTime validFrom) { this.validFrom = validFrom; }
    public LocalDateTime getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDateTime validUntil) { this.validUntil = validUntil; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public String getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(String lastSyncStatus) { this.lastSyncStatus = lastSyncStatus; }
    public String getLastSyncDetail() { return lastSyncDetail; }
    public void setLastSyncDetail(String lastSyncDetail) { this.lastSyncDetail = lastSyncDetail; }
}
