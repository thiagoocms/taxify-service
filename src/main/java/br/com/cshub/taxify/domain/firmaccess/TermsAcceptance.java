package br.com.cshub.taxify.domain.firmaccess;

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
 * Aceite dos termos de uso, da política de privacidade e do acordo de operador no cadastro (F1-RN-11). Nunca editado
 * nem excluído pela aplicação; só o expurgo do cadastro não verificado o apaga, fisicamente (LGPD, F1-RN-03).
 */
@Entity
@Table(name = "terms_acceptances")
public class TermsAcceptance extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "user_id", length = 36, nullable = false, updatable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "accounting_firm_id", length = 36, nullable = false, updatable = false)
    private UUID accountingFirmId;

    @Column(name = "terms_version", length = 50, nullable = false, updatable = false)
    private String termsVersion;

    /** America/Recife. */
    @Column(name = "accepted_at", nullable = false, updatable = false)
    private LocalDateTime acceptedAt;

    @Column(name = "ip", length = 45, nullable = false, updatable = false)
    private String ip;

    public TermsAcceptance() {
    }

    public TermsAcceptance(UUID userId, UUID accountingFirmId, String termsVersion, LocalDateTime acceptedAt, String ip) {
        this.userId = userId;
        this.accountingFirmId = accountingFirmId;
        this.termsVersion = termsVersion;
        this.acceptedAt = acceptedAt;
        this.ip = ip;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getAccountingFirmId() {
        return accountingFirmId;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public String getIp() {
        return ip;
    }
}
