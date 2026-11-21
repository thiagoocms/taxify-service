package br.com.cshub.taxify.domain.billing;

import org.springframework.util.StringUtils;
import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.enumerated.SyncStatus;
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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One accounting firm's connection to one external system (SIEG, Acessórias, Conta Azul...):
 * its credentials - an API key or OAuth tokens - and sync bookkeeping. Every integration
 * reads its credentials from here (see FirmIntegrationService), so adding one doesn't mean
 * adding columns to AccountingFirm.
 */
/*
 * DynamicUpdate: tokens, sync bookkeeping and the job columns are written from different
 * threads (a job refreshing its token while the runner reports progress) - only the columns
 * actually changed go in each UPDATE, so one write doesn't put back another's stale value.
 */
@DynamicUpdate
@Entity
@Table(name = "firm_integrations",
        uniqueConstraints = @UniqueConstraint(name = "uk_firm_integration_type", columnNames = {"accounting_firm_id", "type"}))
public class FirmIntegration extends AbstractAuditingEntity {

    private static final String CATEGORY_SEPARATOR = ",";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_firm_id", nullable = false)
    private AccountingFirm accountingFirm;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
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

    // ---- background job (IntegrationJobRunner): persisted so it survives the request that
    // started it, is visible to every instance, and doubles as the "already running" lock.

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "sync_status", length = 20)
    private SyncStatus syncStatus;

    @Column(name = "sync_started_at")
    private LocalDateTime syncStartedAt;

    @Column(name = "sync_finished_at")
    private LocalDateTime syncFinishedAt;

    /** Refreshed while the job runs - a RUNNING job without a recent heartbeat died (restart) and may be taken over. */
    @Column(name = "sync_heartbeat_at")
    private LocalDateTime syncHeartbeatAt;

    @Column(name = "sync_processed")
    private Integer syncProcessed;

    @Column(name = "sync_total")
    private Integer syncTotal;

    /** Last job's result, as JSON (each integration has its own result type). */
    @Column(name = "sync_result", columnDefinition = "TEXT")
    private String syncResult;

    @Column(name = "sync_error", length = 1000)
    private String syncError;

    public FirmIntegration() {
    }

    public FirmIntegration(AccountingFirm accountingFirm, IntegrationType type) {
        this.accountingFirm = accountingFirm;
        this.type = type;
    }

    /** {@link #feeCategoryIds} as a list - empty means "every receivable of the customer". */
    public List<String> getFeeCategoryIdList() {
        if (!StringUtils.hasText(feeCategoryIds)) {
            return List.of();
        }
        return Arrays.stream(feeCategoryIds.split(CATEGORY_SEPARATOR))
                .map(String::strip)
                .filter(StringUtils::hasText)
                .toList();
    }

    public void setFeeCategoryIdList(List<String> ids) {
        List<String> clean = ids == null ? List.of() : ids.stream().filter(StringUtils::hasText).map(String::strip).distinct().toList();
        this.feeCategoryIds = clean.isEmpty() ? null : String.join(CATEGORY_SEPARATOR, clean);
    }

    /** Has credentials: an API key, or OAuth tokens from a completed authorization. */
    public boolean isConfigured() {
        return StringUtils.hasText(apiKey) || StringUtils.hasText(refreshToken) || StringUtils.hasText(accessToken);
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

    public SyncStatus getSyncStatus() {
        return syncStatus;
    }

    public void setSyncStatus(SyncStatus syncStatus) {
        this.syncStatus = syncStatus;
    }

    public LocalDateTime getSyncStartedAt() {
        return syncStartedAt;
    }

    public void setSyncStartedAt(LocalDateTime syncStartedAt) {
        this.syncStartedAt = syncStartedAt;
    }

    public LocalDateTime getSyncFinishedAt() {
        return syncFinishedAt;
    }

    public void setSyncFinishedAt(LocalDateTime syncFinishedAt) {
        this.syncFinishedAt = syncFinishedAt;
    }

    public LocalDateTime getSyncHeartbeatAt() {
        return syncHeartbeatAt;
    }

    public void setSyncHeartbeatAt(LocalDateTime syncHeartbeatAt) {
        this.syncHeartbeatAt = syncHeartbeatAt;
    }

    public Integer getSyncProcessed() {
        return syncProcessed;
    }

    public void setSyncProcessed(Integer syncProcessed) {
        this.syncProcessed = syncProcessed;
    }

    public Integer getSyncTotal() {
        return syncTotal;
    }

    public void setSyncTotal(Integer syncTotal) {
        this.syncTotal = syncTotal;
    }

    public String getSyncResult() {
        return syncResult;
    }

    public void setSyncResult(String syncResult) {
        this.syncResult = syncResult;
    }

    public String getSyncError() {
        return syncError;
    }

    public void setSyncError(String syncError) {
        this.syncError = syncError;
    }
}
