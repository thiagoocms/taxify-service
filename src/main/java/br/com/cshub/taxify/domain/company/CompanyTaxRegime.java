package br.com.cshub.taxify.domain.company;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.enumerated.BusinessActivity;
import br.com.cshub.taxify.enumerated.SimplesAnnex;
import br.com.cshub.taxify.enumerated.TaxRegime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One period of a company's regime: valid from {@link #validFrom} (first day of a month,
 * null = since always) until the next period starts. The company's own regime fields are
 * the current period, kept in sync by CompanyTaxRegimeService.
 */
@Entity
@Table(name = "company_tax_regimes", indexes = @Index(name = "idx_company_tax_regime_company", columnList = "company_id, valid_from"))
public class CompanyTaxRegime extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tax_regime", length = 30, nullable = false)
    private TaxRegime taxRegime;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "business_activity", length = 30)
    private BusinessActivity businessActivity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "simples_service_annex", length = 5)
    private SimplesAnnex simplesServiceAnnex;

    @Column(name = "valid_from")
    private LocalDate validFrom;

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

    public TaxRegime getTaxRegime() {
        return taxRegime;
    }

    public void setTaxRegime(TaxRegime taxRegime) {
        this.taxRegime = taxRegime;
    }

    public BusinessActivity getBusinessActivity() {
        return businessActivity;
    }

    public void setBusinessActivity(BusinessActivity businessActivity) {
        this.businessActivity = businessActivity;
    }

    public SimplesAnnex getSimplesServiceAnnex() {
        return simplesServiceAnnex;
    }

    public void setSimplesServiceAnnex(SimplesAnnex simplesServiceAnnex) {
        this.simplesServiceAnnex = simplesServiceAnnex;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    /** Same regime, activity and annex - a "change" to it is no change. */
    public boolean sameRegimeAs(TaxRegime regime, BusinessActivity activity, SimplesAnnex annex) {
        return taxRegime == regime && businessActivity == activity && simplesServiceAnnex == annex;
    }
}
