package br.com.cshub.taxify.domain.subscription;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import br.com.cshub.taxify.enumerated.SubscriptionStatus;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.enumerated.BillingCycle;
import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.plan.Plan;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "subscriptions", indexes = {
        @Index(name = "idx_subscription_company_status", columnList = "company_id, status"),
        @Index(name = "idx_subscription_external", columnList = "source, external_contract_id")
})
public class Subscription extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    /** Fee actually charged to the company (negotiated) - typed in MANUAL, synced from the provider's charges otherwise. */
    @Column(name = "fee", precision = 15, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "billing_cycle", length = 20)
    private BillingCycle billingCycle;

    /** Day of the month the fee is due. */
    @Column(name = "billing_day")
    private Integer billingDay;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", length = 20)
    private SubscriptionStatus status;

    /** MANUAL (typed in Taxify) or the provider the contract below lives in. Null = MANUAL. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", length = 30)
    private BillingProviderType source;

    @Column(name = "external_contract_id", length = 100)
    private String externalContractId;

    @Column(name = "external_contract_number", length = 50)
    private String externalContractNumber;

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    /** Last sync problem worth showing (e.g. "2 active contracts") - null when everything matched. */
    @Column(name = "sync_warning", length = 500)
    private String syncWarning;

    public Subscription() {
    }

    public Subscription(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Company getCompany() {
        return company;
    }

    public void setCompany(Company company) {
        this.company = company;
    }

    public Plan getPlan() {
        return plan;
    }

    public void setPlan(Plan plan) {
        this.plan = plan;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public BillingCycle getBillingCycle() {
        return billingCycle;
    }

    public void setBillingCycle(BillingCycle billingCycle) {
        this.billingCycle = billingCycle;
    }

    public Integer getBillingDay() {
        return billingDay;
    }

    public void setBillingDay(Integer billingDay) {
        this.billingDay = billingDay;
    }

    public SubscriptionStatus getStatus() {
        return status == null ? SubscriptionStatus.ACTIVE : status;
    }

    public void setStatus(SubscriptionStatus status) {
        this.status = status;
    }

    public BillingProviderType getSource() {
        return source == null ? BillingProviderType.MANUAL : source;
    }

    /** Contract data (fee, status, contract number, next due date) is owned by the provider - not editable in Taxify. */
    public boolean isSynced() {
        return getSource() != BillingProviderType.MANUAL;
    }

    public void setSource(BillingProviderType source) {
        this.source = source;
    }

    public String getExternalContractId() {
        return externalContractId;
    }

    public void setExternalContractId(String externalContractId) {
        this.externalContractId = externalContractId;
    }

    public String getExternalContractNumber() {
        return externalContractNumber;
    }

    public void setExternalContractNumber(String externalContractNumber) {
        this.externalContractNumber = externalContractNumber;
    }

    public LocalDate getNextDueDate() {
        return nextDueDate;
    }

    public void setNextDueDate(LocalDate nextDueDate) {
        this.nextDueDate = nextDueDate;
    }

    public LocalDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(LocalDateTime lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public String getSyncWarning() {
        return syncWarning;
    }

    public void setSyncWarning(String syncWarning) {
        this.syncWarning = syncWarning;
    }
}
