package br.com.cshub.taxify.domain.pendency;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.PendencyAlertDigestStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * O resumo diário de pendências ALTA de um usuário (AV-RN-09, AV-RN-10): no máximo um por usuário e dia civil de
 * Recife (chave única - a reserva contra duas instâncias). Só contagens; nenhum dado de empresa.
 */
@Entity
@Table(name = "pendency_alert_digests")
public class PendencyAlertDigest extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "accounting_firm_id", length = 36, nullable = false, updatable = false)
    private UUID accountingFirmId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "reference_date", nullable = false, updatable = false)
    private LocalDate referenceDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 10, nullable = false)
    private PendencyAlertDigestStatus status;

    @Column(name = "companies_count", nullable = false)
    private int companiesCount;

    @Column(name = "pendencies_count", nullable = false)
    private int pendenciesCount;

    /** Hora do envio (Recife). */
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    public PendencyAlertDigest() {
    }

    public PendencyAlertDigest(UUID accountingFirmId, UUID userId, LocalDate referenceDate, PendencyAlertDigestStatus status,
                               int companiesCount, int pendenciesCount) {
        this.accountingFirmId = accountingFirmId;
        this.userId = userId;
        this.referenceDate = referenceDate;
        this.status = status;
        this.companiesCount = companiesCount;
        this.pendenciesCount = pendenciesCount;
        setDeleted(false);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountingFirmId() {
        return accountingFirmId;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public PendencyAlertDigestStatus getStatus() {
        return status;
    }

    public void setStatus(PendencyAlertDigestStatus status) {
        this.status = status;
    }

    public int getCompaniesCount() {
        return companiesCount;
    }

    public int getPendenciesCount() {
        return pendenciesCount;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
}
