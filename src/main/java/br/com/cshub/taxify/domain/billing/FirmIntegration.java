package br.com.cshub.taxify.domain.billing;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.enumerated.IntegrationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One accounting firm's connection to one external system (SIEG, Acessórias, Conta Azul...):
 * its credentials - an API key or OAuth tokens - and sync bookkeeping. Every integration
 * reads its credentials from here (see FirmIntegrationService), so adding one doesn't mean
 * adding columns to AccountingFirm.
 */
@Entity
@Table(name = "firm_integrations",
        uniqueConstraints = @UniqueConstraint(name = "uk_firm_integration_type", columnNames = {"accounting_firm_id", "type"}))
public class FirmIntegration extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_firm_id", nullable = false)
    private AccountingFirm accountingFirm;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, nullable = false)
    private IntegrationType type;

    /** Static credential of key-based integrations (SIEG "X-API-Key", Acessórias token). */
    @Column(name = "api_key", columnDefinition = "TEXT")
    private String apiKey;

    @Column(name = "access_token", columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "refresh_token", columnDefinition = "TEXT")
    private String refreshToken;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "connected_at")
    private LocalDateTime connectedAt;

    /** Anti-CSRF "state" of the OAuth authorization in progress - checked on the callback, then cleared. */
    @Column(name = "oauth_state", length = 64)
    private String oauthState;

    @Column(name = "oauth_state_created_at")
    private LocalDateTime oauthStateCreatedAt;

    /** Provider category ids whose receivables count as fees, comma-separated; empty = every receivable of the customer. */
    @Column(name = "fee_category_ids", columnDefinition = "TEXT")
    private String feeCategoryIds;

    /** When the last successful sync started - the next one only asks for what changed after it. */
    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    public FirmIntegration() {
    }

    public FirmIntegration(AccountingFirm accountingFirm, IntegrationType type) {
        this.accountingFirm = accountingFirm;
        this.type = type;
    }

    /** Has credentials: an API key, or OAuth tokens from a completed authorization. */
    public boolean isConfigured() {
        return notBlank(apiKey) || notBlank(refreshToken) || notBlank(accessToken);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public AccountingFirm getAccountingFirm() {
        return accountingFirm;
    }

    public void setAccountingFirm(AccountingFirm accountingFirm) {
        this.accountingFirm = accountingFirm;
    }

    public IntegrationType getType() {
        return type;
    }

    public void setType(IntegrationType type) {
        this.type = type;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public LocalDateTime getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public void setTokenExpiresAt(LocalDateTime tokenExpiresAt) {
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public LocalDateTime getConnectedAt() {
        return connectedAt;
    }

    public void setConnectedAt(LocalDateTime connectedAt) {
        this.connectedAt = connectedAt;
    }

    public String getOauthState() {
        return oauthState;
    }

    public void setOauthState(String oauthState) {
        this.oauthState = oauthState;
    }

    public LocalDateTime getOauthStateCreatedAt() {
        return oauthStateCreatedAt;
    }

    public void setOauthStateCreatedAt(LocalDateTime oauthStateCreatedAt) {
        this.oauthStateCreatedAt = oauthStateCreatedAt;
    }

    public String getFeeCategoryIds() {
        return feeCategoryIds;
    }

    public void setFeeCategoryIds(String feeCategoryIds) {
        this.feeCategoryIds = feeCategoryIds;
    }

    public LocalDateTime getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(LocalDateTime lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }
}
