package br.com.cshub.taxify.domain.capture;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.CapturePriorityStatus;
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
 * Um pedido de "Sincronizar agora" (CP-RN-15 a CP-RN-19): põe a empresa no início da fila SIEG. {@code openKey} vale o
 * {@code company_id} enquanto o pedido está aberto (PENDING ou RESERVED) e nulo depois - a chave única dela garante um
 * só pedido aberto por empresa, mesmo com dois cliques ao mesmo tempo. Horas de America/Recife. Apagado pela limpeza
 * de 90 dias (CP-RN-20).
 */
@Entity
@Table(name = "capture_priority_requests")
public class CapturePriorityRequest extends AbstractAuditingEntity {

    public static final String SCOPE_FIRM = "FIRM";
    public static final String SCOPE_PLATFORM = "PLATFORM";

    @Id
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "company_id", length = 36, nullable = false, updatable = false)
    private UUID companyId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "accounting_firm_id", length = 36, nullable = false, updatable = false)
    private UUID accountingFirmId;

    /** Id do usuário que pediu (nunca o nome nem o login). */
    @Column(name = "requested_by", length = 36, nullable = false, updatable = false)
    private String requestedBy;

    @Column(name = "requested_scope", length = 10, nullable = false, updatable = false)
    private String requestedScope;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private CapturePriorityStatus status;

    @Column(name = "served_at")
    private LocalDateTime servedAt;

    @Column(name = "open_key", length = 36)
    private String openKey;

    public CapturePriorityRequest() {
    }

    public CapturePriorityRequest(UUID companyId, UUID accountingFirmId, UUID requestedBy, String requestedScope,
                                  LocalDateTime requestedAt) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.accountingFirmId = accountingFirmId;
        this.requestedBy = requestedBy == null ? "" : requestedBy.toString();
        this.requestedScope = requestedScope;
        this.requestedAt = requestedAt;
        this.status = CapturePriorityStatus.PENDING;
        this.openKey = companyId.toString();
        setDeleted(false);
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getAccountingFirmId() {
        return accountingFirmId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getRequestedScope() {
        return requestedScope;
    }

    public boolean isFromPlatform() {
        return SCOPE_PLATFORM.equals(requestedScope);
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public CapturePriorityStatus getStatus() {
        return status;
    }

    public LocalDateTime getServedAt() {
        return servedAt;
    }

    public String getOpenKey() {
        return openKey;
    }
}
