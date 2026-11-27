package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.FiscalDocumentInvariant;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.enumerated.InvariantScope;
import br.com.cshub.taxify.enumerated.NfseLayout;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/**
 * NFS-e, national or ABRASF layout. No items. PIS and COFINS are kept as informed, with the withheld flag, as today:
 * {@link #withheldPis()} and {@link #withheldCofins()} give them only when the flag is true, so the retentions keep
 * their meaning.
 */
@Entity
@DiscriminatorValue("NFSE")
public class Nfse extends FiscalDocument {

    public static final String LEGACY_KEY_LABEL = "NFSE";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "nfse_layout", length = 10)
    private NfseLayout layout;

    @Column(name = "nfse_verification_code", length = 60)
    private String verificationCode;

    @Column(name = "nfse_competence")
    private LocalDate competence;

    @Column(name = "nfse_incidence_city_code", length = 7)
    private String incidenceCityCode;

    @Column(name = "nfse_service_code", length = 20)
    private String serviceCode;

    @Column(name = "nfse_municipal_tax_code", length = 30)
    private String municipalTaxCode;

    /** TEXT: the national description may pass 1,000 characters and is no longer cut (P-FD-06). */
    @Column(name = "nfse_service_description", columnDefinition = "TEXT")
    private String serviceDescription;

    @Column(name = "nfse_deductions_amount", precision = 19, scale = 2)
    private BigDecimal deductionsAmount;

    @Embedded
    private IssTax iss = IssTax.notWithheld();

    /** Shared column with the goods invoices (same meaning: the tax informed in the document). */
    @Column(name = "pis_amount", precision = 19, scale = 2)
    private BigDecimal pisAmount;

    @Column(name = "cofins_amount", precision = 19, scale = 2)
    private BigDecimal cofinsAmount;

    /** PIS/COFINS of the note were withheld (national tpRetPisCofins = 1) - otherwise only informed. */
    @Column(name = "nfse_pis_cofins_withheld")
    private Boolean pisCofinsWithheld = Boolean.FALSE;

    @Column(name = "nfse_net_amount", precision = 19, scale = 2)
    private BigDecimal netAmount;

    protected Nfse() {
    }

    public Nfse(Company company, DocumentSourceKey sourceSystem, NfseLayout layout) {
        super(company, sourceSystem);
        this.layout = layout;
    }

    @Override
    public FiscalDocumentType getDocumentType() {
        return FiscalDocumentType.NFSE;
    }

    @Override
    public String legacyKeyLabel() {
        return LEGACY_KEY_LABEL;
    }

    /** FD-RN-10: the layout says the NFS-e is canceled, without a date (no event). */
    public boolean cancelByLayout() {
        return cancel(null);
    }

    /** The PIS withheld: the informed value only when the PIS/COFINS withheld flag is true; otherwise null. */
    public BigDecimal withheldPis() {
        return isPisCofinsWithheld() ? pisAmount : null;
    }

    /** The COFINS withheld: the informed value only when the PIS/COFINS withheld flag is true; otherwise null. */
    public BigDecimal withheldCofins() {
        return isPisCofinsWithheld() ? cofinsAmount : null;
    }

    public boolean isPisCofinsWithheld() {
        return Boolean.TRUE.equals(pisCofinsWithheld);
    }

    @Override
    protected boolean cancellationRequiresDate() {
        return false;
    }

    @Override
    protected void checkInvariants(InvariantScope scope) {
        super.checkInvariants(scope);
        // the layout is always required; a copied row (metadataVersion = 0) may keep an off-size key on UPDATE
        require(FiscalDocumentInvariant.I13_NFSE_LAYOUT_AND_KEY, scope,
                layout != null && (hasKeyOfItsLayout() || toleratesUnparsedCopy(scope)));
        require(FiscalDocumentInvariant.I14_NFSE_WITHHELD_FLAGS, scope,
                iss != null && iss.withheld() != null && pisCofinsWithheld != null);
    }

    /** I-13: NATIONAL = 50-digit key; ABRASF = the synthetic {@code ABRASF-} key. */
    private boolean hasKeyOfItsLayout() {
        if (layout == null) {
            return false;
        }
        return switch (layout) {
            case NATIONAL -> isDigitsOfLength(getAccessKey(), NfseLayout.NATIONAL_KEY_LENGTH);
            case ABRASF -> getAccessKey() != null && getAccessKey().startsWith(NfseLayout.ABRASF_KEY_PREFIX);
        };
    }

    public NfseLayout getLayout() {
        return layout;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public void setVerificationCode(String verificationCode) {
        this.verificationCode = verificationCode;
    }

    public LocalDate getCompetence() {
        return competence;
    }

    public void setCompetence(LocalDate competence) {
        this.competence = competence;
    }

    public String getIncidenceCityCode() {
        return incidenceCityCode;
    }

    public void setIncidenceCityCode(String incidenceCityCode) {
        this.incidenceCityCode = incidenceCityCode;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public String getMunicipalTaxCode() {
        return municipalTaxCode;
    }

    public void setMunicipalTaxCode(String municipalTaxCode) {
        this.municipalTaxCode = municipalTaxCode;
    }

    public String getServiceDescription() {
        return serviceDescription;
    }

    public void setServiceDescription(String serviceDescription) {
        this.serviceDescription = serviceDescription;
    }

    public BigDecimal getDeductionsAmount() {
        return deductionsAmount;
    }

    public void setDeductionsAmount(BigDecimal deductionsAmount) {
        this.deductionsAmount = money(deductionsAmount);
    }

    public IssTax getIss() {
        return iss;
    }

    public void setIss(IssTax iss) {
        this.iss = iss;
    }

    public BigDecimal getPisAmount() {
        return pisAmount;
    }

    public void setPisAmount(BigDecimal pisAmount) {
        this.pisAmount = money(pisAmount);
    }

    public BigDecimal getCofinsAmount() {
        return cofinsAmount;
    }

    public void setCofinsAmount(BigDecimal cofinsAmount) {
        this.cofinsAmount = money(cofinsAmount);
    }

    public Boolean getPisCofinsWithheld() {
        return pisCofinsWithheld;
    }

    public void setPisCofinsWithheld(Boolean pisCofinsWithheld) {
        this.pisCofinsWithheld = pisCofinsWithheld;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(BigDecimal netAmount) {
        this.netAmount = money(netAmount);
    }
}
