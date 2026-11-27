package br.com.cshub.taxify.domain.accountingfirm;

import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import br.com.cshub.taxify.enumerated.BillingProviderType;
import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * The tenant boundary: every {@link br.com.cshub.taxify.domain.user.User} and
 * {@link br.com.cshub.taxify.domain.company.Company} belongs to exactly one accounting
 * firm, and TenantAccessService uses that to make sure one firm's team never sees another
 * firm's data.
 */
@Entity
@Table(name = "accounting_firms")
public class AccountingFirm extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    /*
     * SIEG / Acessórias / Conta Azul credentials live in firm_integrations (FirmIntegration);
     * the old sieg_api_key / acessorias_* columns stay in the table only so an older image
     * still runs after a rollback - see FirmIntegrationMigration.
     */

    /** Where this firm's contracts and fee charges come from - see BillingProvider. Null = MANUAL. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "billing_provider", length = 30)
    private BillingProviderType billingProvider;

    /** CNPJ do escritório, só dígitos - único entre os escritórios, fora de {@code documents} (F1-RN-02). Nulo nos migrados. */
    @Column(name = "document_number", length = 14)
    private String documentNumber;

    /** E-mail financeiro, separado do login (F1-RN-02). */
    @Column(name = "billing_email", length = 300)
    private String billingEmail;

    /** Telefone/WhatsApp de contato, só dígitos (DDD + número). */
    @Column(name = "contact_phone", length = 11)
    private String contactPhone;

    public AccountingFirm() {
    }

    public AccountingFirm(UUID id) {
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

    public BillingProviderType getBillingProvider() {
        return billingProvider == null ? BillingProviderType.MANUAL : billingProvider;
    }

    public void setBillingProvider(BillingProviderType billingProvider) {
        this.billingProvider = billingProvider;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }

    public String getBillingEmail() {
        return billingEmail;
    }

    public void setBillingEmail(String billingEmail) {
        this.billingEmail = billingEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }
}
