package br.com.cshub.taxify.domain.password;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
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
 * Um link de redefinição de senha (SN-RN-03). Guarda só o SHA-256 do token. {@code requestedAt}, {@code expiresAt},
 * {@code usedAt} e {@code invalidatedAt} são horas de America/Recife (ClockConfig) - é o que contam a validade, os
 * limites e a limpeza; o {@code created_date} da auditoria é a hora da JVM. Nunca é editado pela tela: vence, é usado,
 * é invalidado por um pedido novo ou é apagado fisicamente pela limpeza de 48 h (SN-RN-15).
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken extends AbstractAuditingEntity {

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

    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    /** Um pedido novo ou uma troca de senha invalidou este link. */
    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;

    /** IP de quem pediu - só para o limite por IP; apagado na redefinição e na limpeza de 48 h. */
    @Column(name = "requested_ip", length = 45)
    private String requestedIp;

    public PasswordResetToken() {
    }

    public PasswordResetToken(UUID userId, String tokenHash, LocalDateTime requestedAt, LocalDateTime expiresAt, String requestedIp) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
        this.requestedIp = requestedIp;
        setDeleted(false);
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

    public LocalDateTime getRequestedAt() {
        return requestedAt;
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

    public LocalDateTime getInvalidatedAt() {
        return invalidatedAt;
    }

    public void setInvalidatedAt(LocalDateTime invalidatedAt) {
        this.invalidatedAt = invalidatedAt;
    }

    public String getRequestedIp() {
        return requestedIp;
    }

    /** Não usado, não invalidado e até {@code expiresAt}, inclusive (vencido = agora depois de expires_at). */
    public boolean isUsableAt(LocalDateTime now) {
        return !isDeleted() && usedAt == null && invalidatedAt == null && !now.isAfter(expiresAt);
    }
}
