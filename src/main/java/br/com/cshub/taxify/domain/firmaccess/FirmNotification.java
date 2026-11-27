package br.com.cshub.taxify.domain.firmaccess;

import br.com.cshub.taxify.enumerated.FirmNotificationType;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
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
 * Aviso de fim do teste já enviado (F1-RN-16): único por escritório, tipo e data de fim do teste. Nunca editado nem
 * excluído pela aplicação; só o expurgo do cadastro não verificado o apaga, fisicamente (LGPD, F1-RN-03).
 */
@Entity
@Table(name = "firm_notifications")
public class FirmNotification extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "accounting_firm_id", length = 36, nullable = false, updatable = false)
    private UUID accountingFirmId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "type", length = 20, nullable = false, updatable = false)
    private FirmNotificationType type;

    /** O {@code trial_ends_on} a que o aviso se refere. */
    @Column(name = "reference_date", nullable = false, updatable = false)
    private LocalDate referenceDate;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private LocalDateTime sentAt;

    public FirmNotification() {
    }

    public FirmNotification(UUID accountingFirmId, FirmNotificationType type, LocalDate referenceDate, LocalDateTime sentAt) {
        this.accountingFirmId = accountingFirmId;
        this.type = type;
        this.referenceDate = referenceDate;
        this.sentAt = sentAt;
    }

    public UUID getAccountingFirmId() {
        return accountingFirmId;
    }

    public FirmNotificationType getType() {
        return type;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }
}
