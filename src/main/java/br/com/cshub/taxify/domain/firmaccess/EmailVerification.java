package br.com.cshub.taxify.domain.firmaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Um link de verificação do e-mail do cadastro (F1-RN-03). Guarda só o SHA-256 do token. Horários em Recife.
 *
 * <p>Exceção de propósito à convenção de AbstractAuditingEntity: {@code created_date} é a hora de emissão pelo relógio
 * de Recife (ClockConfig), que conta nos limites de reenvio por hora e por dia - o listener de auditoria a trocaria pela
 * hora da JVM (UTC no container). O link não tem soft delete: vence, é usado ou é apagado fisicamente com o cadastro
 * não verificado (LGPD, F1-RN-03).
 */
@Entity
@Table(name = "email_verifications")
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", length = 64, nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    /** Usado na verificação, ou invalidado por um reenvio. */
    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** Reenvio (conta no limite por hora) ou o e-mail do próprio cadastro (não conta). */
    @Column(name = "resend", nullable = false, updatable = false)
    private boolean resend;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    /** IP de quem pediu o reenvio (teto diário por IP); nulo no e-mail do cadastro. */
    @Column(name = "requested_ip", length = 45)
    private String requestedIp;

    public EmailVerification() {
    }

    public EmailVerification(UUID userId, String tokenHash, LocalDateTime createdDate, LocalDateTime expiresAt, boolean resend) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdDate = createdDate;
        this.expiresAt = expiresAt;
        this.resend = resend;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(LocalDateTime usedAt) {
        this.usedAt = usedAt;
    }

    public boolean isResend() {
        return resend;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }

    public String getRequestedIp() {
        return requestedIp;
    }

    public void setRequestedIp(String requestedIp) {
        this.requestedIp = requestedIp;
    }

    /** Vale uma vez só e até {@code expiresAt}, inclusive (vencido = agora depois de expires_at). */
    public boolean isUsableAt(LocalDateTime now) {
        return usedAt == null && !now.isAfter(expiresAt);
    }
}
