package br.com.cshub.taxify.domain.firmaccess;

import br.com.cshub.taxify.enumerated.BlockedReason;
import br.com.cshub.taxify.enumerated.FirmSubscriptionStatus;
import br.com.cshub.taxify.enumerated.SubscriptionSource;

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
 * Assinatura do escritório ao Taxify (o Plano Taxify, vendido pela CS Hub ao escritório). Não confundir com
 * {@link br.com.cshub.taxify.domain.subscription.Subscription}, o Plano de honorário de uma empresa cliente.
 * Uma linha por escritório; a situação efetiva de cada requisição é calculada por {@link br.com.cshub.taxify.service.firmaccess.FirmAccessRules}.
 */
@Entity
@Table(name = "firm_subscriptions")
public class FirmSubscription extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "accounting_firm_id", length = 36, nullable = false, updatable = false)
    private UUID accountingFirmId;

    /** Plano Taxify (fase 2); nulo na fase 1. */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "platform_plan_id", length = 36)
    private UUID platformPlanId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 30, nullable = false)
    private FirmSubscriptionStatus status;

    /** Último dia do teste, inclusive (vale até 23:59:59 de Recife). */
    @Column(name = "trial_ends_on")
    private LocalDate trialEndsOn;

    /** Último dia da liberação, inclusive; nulo = sem fim. */
    @Column(name = "access_until")
    private LocalDate accessUntil;

    @Column(name = "blocked_since")
    private LocalDate blockedSince;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "blocked_reason", length = 30)
    private BlockedReason blockedReason;

    /** Criada pela migração dos escritórios que já existiam (F1-RN-13) - entra na fila de revisão. */
    @Column(name = "migrated", nullable = false)
    private boolean migrated;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 50)
    private String reviewedBy;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", length = 20, nullable = false)
    private SubscriptionSource source = SubscriptionSource.MANUAL;

    @Column(name = "gateway_customer_id", length = 100)
    private String gatewayCustomerId;

    @Column(name = "gateway_subscription_id", length = 100)
    private String gatewaySubscriptionId;

    @Column(name = "notes", length = 500)
    private String notes;

    /** Recuperação dos meses bloqueados ainda em andamento: de quando a quando (nulos = nada a recuperar). */
    @Column(name = "recovery_from")
    private LocalDate recoveryFrom;

    @Column(name = "recovery_until")
    private LocalDate recoveryUntil;

    public LocalDate getRecoveryFrom() {
        return recoveryFrom;
    }

    public LocalDate getRecoveryUntil() {
        return recoveryUntil;
    }

    /** Junta um período novo ao que ainda está em recuperação (novo bloqueio antes de concluir). */
    public void addRecovery(LocalDate from, LocalDate until) {
        this.recoveryFrom = recoveryFrom == null || from.isBefore(recoveryFrom) ? from : recoveryFrom;
        this.recoveryUntil = recoveryUntil == null || until.isAfter(recoveryUntil) ? until : recoveryUntil;
    }

    public void clearRecovery() {
        this.recoveryFrom = null;
        this.recoveryUntil = null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountingFirmId() {
        return accountingFirmId;
    }

    public void setAccountingFirmId(UUID accountingFirmId) {
        this.accountingFirmId = accountingFirmId;
    }

    public UUID getPlatformPlanId() {
        return platformPlanId;
    }

    public FirmSubscriptionStatus getStatus() {
        return status;
    }

    public void setStatus(FirmSubscriptionStatus status) {
        this.status = status;
    }

    public LocalDate getTrialEndsOn() {
        return trialEndsOn;
    }

    public void setTrialEndsOn(LocalDate trialEndsOn) {
        this.trialEndsOn = trialEndsOn;
    }

    public LocalDate getAccessUntil() {
        return accessUntil;
    }

    public void setAccessUntil(LocalDate accessUntil) {
        this.accessUntil = accessUntil;
    }

    public LocalDate getBlockedSince() {
        return blockedSince;
    }

    public void setBlockedSince(LocalDate blockedSince) {
        this.blockedSince = blockedSince;
    }

    public BlockedReason getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(BlockedReason blockedReason) {
        this.blockedReason = blockedReason;
    }

    public boolean isMigrated() {
        return migrated;
    }

    public void setMigrated(boolean migrated) {
        this.migrated = migrated;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public SubscriptionSource getSource() {
        return source;
    }

    public void setSource(SubscriptionSource source) {
        this.source = source;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /** O que a trilha guarda como "antes" e "depois" de uma mudança. */
    public FirmSubscriptionSnapshot snapshot() {
        return new FirmSubscriptionSnapshot(status, trialEndsOn, accessUntil, blockedSince, blockedReason, migrated,
                reviewedAt != null);
    }
}
