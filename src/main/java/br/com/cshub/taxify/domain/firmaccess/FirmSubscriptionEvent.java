package br.com.cshub.taxify.domain.firmaccess;

import br.com.cshub.taxify.enumerated.FirmEventType;

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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Um item da trilha da assinatura do escritório (F1-RN-18). Nunca alterado depois de gravado.
 *
 * <p>Exceção de propósito à convenção de AbstractAuditingEntity: a trilha é só de inclusão, sem soft delete, com o seu
 * próprio {@code actor} (login, {@code system}, {@code signup}) e o {@code created_date} do relógio de Recife, que ordena
 * a trilha e aparece no console. Também sem chave estrangeira para o escritório: o expurgo do cadastro não verificado
 * (F1-RN-03) apaga o escritório e o evento UNVERIFIED_PURGED fica, sem dado pessoal.
 */
@Entity
@Table(name = "firm_subscription_events")
public class FirmSubscriptionEvent {

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
    @Column(name = "type", length = 40, nullable = false, updatable = false)
    private FirmEventType type;

    @Column(name = "before_json", columnDefinition = "text", updatable = false)
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "text", updatable = false)
    private String afterJson;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    /** Login de quem fez, {@code system} (migração, agendador) ou {@code gateway} (fase 2). */
    @Column(name = "actor", length = 50, nullable = false, updatable = false)
    private String actor;

    /** America/Recife. */
    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    public FirmSubscriptionEvent() {
    }

    public FirmSubscriptionEvent(UUID accountingFirmId, FirmEventType type, String beforeJson, String afterJson,
                                 String reason, String actor, LocalDateTime createdDate) {
        this.accountingFirmId = accountingFirmId;
        this.type = type;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.reason = reason;
        this.actor = actor;
        this.createdDate = createdDate;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountingFirmId() {
        return accountingFirmId;
    }

    public FirmEventType getType() {
        return type;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public String getReason() {
        return reason;
    }

    public String getActor() {
        return actor;
    }

    public LocalDateTime getCreatedDate() {
        return createdDate;
    }
}
