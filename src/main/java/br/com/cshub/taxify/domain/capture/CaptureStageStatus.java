package br.com.cshub.taxify.domain.capture;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.CaptureStage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * O último resultado de uma etapa da captura de uma empresa (CP-RN-10): uma linha por (company_id, stage), gravada
 * por upsert ({@link br.com.cshub.taxify.repository.CaptureStageStatusRepository}) ao fim de cada etapa. Só códigos da
 * lista fechada e status numéricos (CP-RN-11): nada de mensagem do provedor, CNPJ, chave ou token. Horas de
 * America/Recife. Lida pelo painel; não é editada pela tela.
 */
@Entity
@Table(name = "capture_stage_status")
public class CaptureStageStatus extends AbstractAuditingEntity {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "company_id", length = 36, nullable = false, updatable = false)
    private UUID companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 30, nullable = false, updatable = false)
    private CaptureStage stage;

    @Column(name = "last_outcome", length = 10, nullable = false)
    private String lastOutcome;

    @Column(name = "last_outcome_at", nullable = false)
    private LocalDateTime lastOutcomeAt;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    @Column(name = "failure_code", length = 40)
    private String failureCode;

    @Column(name = "provider_status", length = 4)
    private String providerStatus;

    @Column(name = "warning_code", length = 40)
    private String warningCode;

    @Column(name = "warning_count")
    private Integer warningCount;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public CaptureStage getStage() {
        return stage;
    }

    public String getLastOutcome() {
        return lastOutcome;
    }

    public boolean isFailure() {
        return FAILURE.equals(lastOutcome);
    }

    public LocalDateTime getLastOutcomeAt() {
        return lastOutcomeAt;
    }

    public LocalDateTime getLastSuccessAt() {
        return lastSuccessAt;
    }

    public LocalDateTime getLastFailureAt() {
        return lastFailureAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public String getWarningCode() {
        return warningCode;
    }

    public Integer getWarningCount() {
        return warningCount;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }
}
