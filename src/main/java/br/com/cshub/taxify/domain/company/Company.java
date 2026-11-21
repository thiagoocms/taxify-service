package br.com.cshub.taxify.domain.company;

import java.util.List;
import java.util.Collection;
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
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "tax_regime_type", length = 30)
    private TaxRegime taxRegimeType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "business_activity", length = 30)
    private BusinessActivity businessActivity;

    /**
     * Simples Nacional annex for the company's service revenue (III, IV or V - depends on the
     * activity and the fator R). Goods follow the CFOP: own production → II, resale → I.
     * Null = III.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "simples_service_annex", length = 5)
    private SimplesAnnex simplesServiceAnnex;

    /** Data de abertura (CNPJ) - início de atividade para o RBT12 proporcional. */
    @Column(name = "opening_date")
    private LocalDate openingDate;

    @Column(name = "email", length = 200)
    private String email;

    /** Inscrição municipal. */
    @Column(name = "municipal_registration", length = 30)
    private String municipalRegistration;

    /** CNAE principal, só dígitos. */
    @Column(name = "main_cnae", length = 10)
    private String mainCnae;

    /** CNAEs secundários, só dígitos, separados por vírgula. */
    @Column(name = "secondary_cnaes", length = 500)
    private String secondaryCnaes;

    @Column(name = "address_street", length = 200)
    private String addressStreet;

    @Column(name = "address_number", length = 20)
    private String addressNumber;

    @Column(name = "address_complement", length = 100)
    private String addressComplement;

    @Column(name = "address_district", length = 100)
    private String addressDistrict;

    /** Código IBGE do município. */
    @Column(name = "address_city_code", length = 7)
    private String addressCityCode;

    @Column(name = "address_city", length = 100)
    private String addressCity;

    /** CEP, só dígitos. */
    @Column(name = "address_zip_code", length = 8)
    private String addressZipCode;

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
     * Sets the structured regime; the label follows unless it already says the same
     * ("Lucro Real - Comércio - Filial" stays as Acessórias wrote it).
     */
    public void applyStructuredRegime(TaxRegime regime, BusinessActivity activity, SimplesAnnex annex) {
        this.taxRegimeType = regime;
        this.businessActivity = activity;
        this.simplesServiceAnnex = annex;
        TaxRegimes.Parsed label = TaxRegimes.parse(taxRegime);
        if (label == null || label.regime() != regime || label.activity() != activity) {
            this.taxRegime = TaxRegimes.label(regime, activity);
        }
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

    public LocalDate getOpeningDate() {
        return openingDate;
    }

    public void setOpeningDate(LocalDate openingDate) {
        this.openingDate = openingDate;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getMunicipalRegistration() {
        return municipalRegistration;
    }

    public void setMunicipalRegistration(String municipalRegistration) {
        this.municipalRegistration = municipalRegistration;
    }

    public String getMainCnae() {
        return mainCnae;
    }

    public void setMainCnae(String mainCnae) {
        this.mainCnae = mainCnae;
    }

    public String getSecondaryCnaes() {
        return secondaryCnaes;
    }

    public void setSecondaryCnaes(String secondaryCnaes) {
        this.secondaryCnaes = secondaryCnaes;
    }

    public String getAddressStreet() {
        return addressStreet;
    }

    public void setAddressStreet(String addressStreet) {
        this.addressStreet = addressStreet;
    }

    public String getAddressNumber() {
        return addressNumber;
    }

    public void setAddressNumber(String addressNumber) {
        this.addressNumber = addressNumber;
    }

    public String getAddressComplement() {
        return addressComplement;
    }

    public void setAddressComplement(String addressComplement) {
        this.addressComplement = addressComplement;
    }

    public String getAddressDistrict() {
        return addressDistrict;
    }

    public void setAddressDistrict(String addressDistrict) {
        this.addressDistrict = addressDistrict;
    }

    public String getAddressCityCode() {
        return addressCityCode;
    }

    public void setAddressCityCode(String addressCityCode) {
        this.addressCityCode = addressCityCode;
    }

    public String getAddressCity() {
        return addressCity;
    }

    public void setAddressCity(String addressCity) {
        this.addressCity = addressCity;
    }

    public String getAddressZipCode() {
        return addressZipCode;
    }

    public void setAddressZipCode(String addressZipCode) {
        this.addressZipCode = addressZipCode;
    }

    /** The state registrations held in {@link #stateRegistration} ("IE/UF; IE/UF"). */
    public List<StateRegistration> getStateRegistrations() {
        return StateRegistration.parse(stateRegistration);
    }

    /** Stores the registrations in the normalized text form. */
    public void replaceStateRegistrations(Collection<StateRegistration> registrations) {
        this.stateRegistration = StateRegistration.format(registrations);
    }
}
