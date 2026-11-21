package br.com.cshub.taxify.domain.billing;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.BillingProviderType;
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

import java.util.UUID;

/** Which customer of an external system (per provider) a Taxify company is - matched by CNPJ/CPF. */
@Entity
@Table(name = "external_links",
        uniqueConstraints = @UniqueConstraint(name = "uk_external_link_company_provider", columnNames = {"company_id", "provider"}))
public class ExternalLink extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 30, nullable = false)
    private BillingProviderType provider;

    @Column(name = "external_id", length = 100, nullable = false)
    private String externalId;

    @Column(name = "external_name", length = 255)
    private String externalName;

    public ExternalLink() {
    }

    public ExternalLink(Company company, BillingProviderType provider, String externalId, String externalName) {
        this.company = company;
        this.provider = provider;
        this.externalId = externalId;
        this.externalName = externalName;
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

    public BillingProviderType getProvider() {
        return provider;
    }

    public void setProvider(BillingProviderType provider) {
        this.provider = provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getExternalName() {
        return externalName;
    }

    public void setExternalName(String externalName) {
        this.externalName = externalName;
    }
}
