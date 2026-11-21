package br.com.cshub.taxify.domain.company;

import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.enumerated.SimplesAnnex;
import br.com.cshub.taxify.enumerated.BusinessActivity;
import br.com.cshub.taxify.common.TaxRegimes;
import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.document.Document;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "companies")
public class Company extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "name", length = 150, nullable = false)
    private String name;

    /**
     * The accounting firm this client company belongs to - every query scopes on this so
     * one firm's team never sees another firm's companies (see TenantAccessService).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "accounting_firm_id", nullable = false)
    private AccountingFirm accountingFirm;

    // Optional registration data - all nullable; filled by hand or by the Acessórias import.

    /** Nome fantasia. */
    @Column(name = "trade_name", length = 150)
    private String tradeName;

    @Column(name = "phone", length = 30)
    private String phone;

    /** UF of the company's main establishment. */
    @Column(name = "uf", length = 2)
    private String uf;

    /** Label as the source system names it (e.g. "Simples Nacional - Comércio e Serviço") - kept for display. */
    @Column(name = "tax_regime", length = 100)
    private String taxRegime;

    /** Structured regime - what the period assessment uses. Parsed from {@link #taxRegime} when imported. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_regime_type", length = 30)
    private TaxRegime taxRegimeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "business_activity", length = 30)
    private BusinessActivity businessActivity;

    /**
     * Simples Nacional annex for the company's service revenue (III, IV or V - depends on the
     * activity and the fator R). Goods follow the CFOP: own production → II, resale → I.
     * Null = III.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "simples_service_annex", length = 5)
    private SimplesAnnex simplesServiceAnnex;

    /** Inscrição(ões) estadual(is) - several are joined as "IE/UF; IE/UF". */
    @Column(name = "state_registration", length = 255)
    private String stateRegistration;

    /** Grupo de empresas the firm uses to organize its clients. */
    @Column(name = "company_group", length = 100)
    private String companyGroup;

    /** Null = unknown (registered by hand); false = no longer an active client. */
    @Column(name = "active")
    private Boolean active;

    @Column(name = "client_since")
    private LocalDate clientSince;

    @Column(name = "client_until")
    private LocalDate clientUntil;

    /** This company's own ID in Acessórias - set only by the import (AcessoriasImportService), never by the API's create/update. */
    @Column(name = "acessorias_id", length = 20)
    private String acessoriasId;

    public Company() {
    }

    public Company(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccountingFirm getAccountingFirm() {
        return accountingFirm;
    }

    public void setAccountingFirm(AccountingFirm accountingFirm) {
        this.accountingFirm = accountingFirm;
    }

    public String getTradeName() {
        return tradeName;
    }

    public void setTradeName(String tradeName) {
        this.tradeName = tradeName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getUf() {
        return uf;
    }

    public void setUf(String uf) {
        this.uf = uf;
    }

    public String getTaxRegime() {
        return taxRegime;
    }

    public void setTaxRegime(String taxRegime) {
        this.taxRegime = taxRegime;
    }

    public String getStateRegistration() {
        return stateRegistration;
    }

    public void setStateRegistration(String stateRegistration) {
        this.stateRegistration = stateRegistration;
    }

    public String getCompanyGroup() {
        return companyGroup;
    }

    public void setCompanyGroup(String companyGroup) {
        this.companyGroup = companyGroup;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public LocalDate getClientSince() {
        return clientSince;
    }

    public void setClientSince(LocalDate clientSince) {
        this.clientSince = clientSince;
    }

    public LocalDate getClientUntil() {
        return clientUntil;
    }

    public void setClientUntil(LocalDate clientUntil) {
        this.clientUntil = clientUntil;
    }

    public String getAcessoriasId() {
        return acessoriasId;
    }

    public void setAcessoriasId(String acessoriasId) {
        this.acessoriasId = acessoriasId;
    }

    public TaxRegime getTaxRegimeType() {
        return taxRegimeType;
    }

    public void setTaxRegimeType(TaxRegime taxRegimeType) {
        this.taxRegimeType = taxRegimeType;
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

    /**
     * Sets the label and, from it, the structured regime and activity - what an import from
     * another system does (the annex is never in the label, it is kept).
     */
    public void applyTaxRegimeLabel(String label) {
        this.taxRegime = label;
        TaxRegimes.Parsed parsed = TaxRegimes.parse(label);
        this.taxRegimeType = parsed == null ? null : parsed.regime();
        this.businessActivity = parsed == null ? null : parsed.activity();
    }
}
