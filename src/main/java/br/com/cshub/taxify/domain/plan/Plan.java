package br.com.cshub.taxify.domain.plan;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import java.math.BigDecimal;
import br.com.cshub.taxify.enumerated.BillingCycle;
import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "plans")
public class Plan extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    /** The firm that offers the plan - each firm has its own catalog (null only on legacy rows not yet assigned). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_firm_id")
    private AccountingFirm accountingFirm;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "plan_services", joinColumns = @JoinColumn(name = "plan_id"))
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "service", length = 40, nullable = false)
    private Set<PlanServiceEnum> services = new HashSet<>();

    /** Reference (list) price of the plan; each subscription's negotiated fee may differ. */
    @Column(name = "list_price", precision = 15, scale = 2)
    private BigDecimal listPrice;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "billing_cycle", length = 20)
    private BillingCycle billingCycle;

    public Plan() {
    }

    public Plan(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<PlanServiceEnum> getServices() {
        return services;
    }

    public void setServices(Set<PlanServiceEnum> services) {
        this.services = services != null ? services : new HashSet<>();
    }

    public BigDecimal getListPrice() {
        return listPrice;
    }

    public void setListPrice(BigDecimal listPrice) {
        this.listPrice = listPrice;
    }

    public BillingCycle getBillingCycle() {
        return billingCycle;
    }

    public void setBillingCycle(BillingCycle billingCycle) {
        this.billingCycle = billingCycle;
    }

    public AccountingFirm getAccountingFirm() {
        return accountingFirm;
    }

    public void setAccountingFirm(AccountingFirm accountingFirm) {
        this.accountingFirm = accountingFirm;
    }
}
