package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.CteTakerRole;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.FiscalDocumentInvariant;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.enumerated.InvariantScope;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static br.com.cshub.taxify.domain.fiscaldocument.FiscalDecimals.money;

/**
 * CT-e, model 57. The issuer (carrier) is {@code issuer_*}, the recipient ({@code dest}) is {@code recipient_*} and
 * {@code vTPrest} is {@code total_amount}. The direction of a CT-e is informative only: no fiscal routine reads it
 * (CX-RN-40, P-FD-04).
 */
@Entity
@DiscriminatorValue("CTE")
public class Cte extends FiscalDocument {

    public static final String MODEL = "57";
    public static final String LEGACY_KEY_LABEL = "CTE";
    /** Rows copied from the old model without the CT-e parser (FD-RN-15). */
    public static final int NOT_PARSED_VERSION = 0;

    @Column(name = "cte_type", length = 2)
    private String cteType;

    @Column(name = "cte_service_type", length = 2)
    private String serviceType;

    @Column(name = "cte_modal", length = 2)
    private String modal;

    @Column(name = "cte_cfop", length = 4)
    private String cfop;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "document", column = @Column(name = "sender_document", length = 14)),
            @AttributeOverride(name = "documentType", column = @Column(name = "sender_document_type", length = 10)),
            @AttributeOverride(name = "name", column = @Column(name = "sender_name", length = 200))
    })
    private CteParty sender;

    @Column(name = "sender_uf", length = 2, columnDefinition = "char(2)")
    private String senderUf;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "document", column = @Column(name = "shipper_document", length = 14)),
            @AttributeOverride(name = "documentType", column = @Column(name = "shipper_document_type", length = 10)),
            @AttributeOverride(name = "name", column = @Column(name = "shipper_name", length = 200))
    })
    private CteParty shipper;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "document", column = @Column(name = "receiver_document", length = 14)),
            @AttributeOverride(name = "documentType", column = @Column(name = "receiver_document_type", length = 10)),
            @AttributeOverride(name = "name", column = @Column(name = "receiver_name", length = 200))
    })
    private CteParty receiver;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "document", column = @Column(name = "taker_document", length = 14)),
            @AttributeOverride(name = "documentType", column = @Column(name = "taker_document_type", length = 10)),
            @AttributeOverride(name = "name", column = @Column(name = "taker_name", length = 200))
    })
    private CteParty taker;

    @Column(name = "taker_uf", length = 2, columnDefinition = "char(2)")
    private String takerUf;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cte_taker_role", length = 10)
    private CteTakerRole takerRole;

    @Embedded
    private Route route;

    @Column(name = "cte_amount_receivable", precision = 19, scale = 2)
    private BigDecimal amountReceivable;

    @Column(name = "cte_cargo_value", precision = 19, scale = 2)
    private BigDecimal cargoValue;

    @Embedded
    private CteIcms icms;

    @Embedded
    private CteParticipation participation;

    protected Cte() {
    }

    public Cte(Company company, DocumentSourceKey sourceSystem) {
        super(company, sourceSystem);
        setModel(MODEL);
    }

    @Override
    public FiscalDocumentType getDocumentType() {
        return FiscalDocumentType.CTE;
    }

    @Override
    public String legacyKeyLabel() {
        return LEGACY_KEY_LABEL;
    }

    /**
     * FD-RN-08: records every role of the company and derives the main one by precedence; the direction is
     * {@code OUTBOUND} for the issuer, {@code INBOUND} for the taker and none for the others (informative only).
     */
    public void assignParticipation(CteParticipation participation) {
        this.participation = participation;
        CompanyDocumentRole role = participation == null ? CompanyDocumentRole.THIRD_PARTY : participation.primaryRole();
        assignRole(role, directionOf(role));
    }

    static FiscalDirection directionOf(CompanyDocumentRole role) {
        return switch (role) {
            case ISSUER -> FiscalDirection.OUTBOUND;
            case TAKER -> FiscalDirection.INBOUND;
            default -> null;
        };
    }

    /**
     * I-04-T: the direction of a CT-e is derived from its role (FD-RN-08) - ISSUER = OUTBOUND, TAKER = INBOUND, every
     * other role (RECIPIENT included) none. The only tolerance: on UPDATE, a CT-e copied from the old model
     * ({@code metadataVersion = 0}) with role RECIPIENT keeps the INBOUND it had there (parity, spec 3.12 D-03).
     */
    @Override
    protected void checkDirection(InvariantScope scope) {
        CompanyDocumentRole role = getCompanyRole();
        boolean derived = getDirection() == directionOf(role);
        boolean copiedRecipient = !scope.isCreate() && isUnparsedCopy()
                && role == CompanyDocumentRole.RECIPIENT && getDirection() == FiscalDirection.INBOUND;
        require(FiscalDocumentInvariant.I04T_CTE_DIRECTION_FROM_ROLE, scope, derived || copiedRecipient);
    }

    /** I-09: a CT-e copied from the old model without the CT-e parser may have no file. */
    @Override
    protected boolean requiresXmlFile() {
        return getMetadataVersion() == null || getMetadataVersion() != NOT_PARSED_VERSION;
    }

    @Override
    protected Stream<? extends PartyIdentity> parties() {
        return Stream.of(getIssuer(), getRecipient(), sender, shipper, receiver, taker);
    }

    @Override
    protected void checkInvariants(InvariantScope scope) {
        super.checkInvariants(scope);
        require(FiscalDocumentInvariant.I12_CTE_MODEL_AND_KEY, scope,
                MODEL.equals(getModel()) && isDigitsOfLength(getAccessKey(), ACCESS_KEY_44));
        // I-15: a CT-e in which the company is in no group (upload) is THIRD_PARTY and needs no participation flag
        require(FiscalDocumentInvariant.I15_CTE_PARTICIPATION, scope,
                getCompanyRole() == CompanyDocumentRole.THIRD_PARTY || (participation != null && participation.hasAnyRole()));
    }

    public String getCteType() {
        return cteType;
    }

    public void setCteType(String cteType) {
        this.cteType = cteType;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public String getModal() {
        return modal;
    }

    public void setModal(String modal) {
        this.modal = modal;
    }

    public String getCfop() {
        return cfop;
    }

    public void setCfop(String cfop) {
        this.cfop = FiscalText.trimSpaces(cfop);
    }

    public CteParty getSender() {
        return sender;
    }

    public void setSender(CteParty sender) {
        this.sender = sender;
    }

    public String getSenderUf() {
        return senderUf;
    }

    public void setSenderUf(String senderUf) {
        this.senderUf = senderUf;
    }

    public CteParty getShipper() {
        return shipper;
    }

    public void setShipper(CteParty shipper) {
        this.shipper = shipper;
    }

    public CteParty getReceiver() {
        return receiver;
    }

    public void setReceiver(CteParty receiver) {
        this.receiver = receiver;
    }

    public CteParty getTaker() {
        return taker;
    }

    public void setTaker(CteParty taker) {
        this.taker = taker;
    }

    public String getTakerUf() {
        return takerUf;
    }

    public void setTakerUf(String takerUf) {
        this.takerUf = takerUf;
    }

    public CteTakerRole getTakerRole() {
        return takerRole;
    }

    public void setTakerRole(CteTakerRole takerRole) {
        this.takerRole = takerRole;
    }

    public Route getRoute() {
        return route;
    }

    public void setRoute(Route route) {
        this.route = route;
    }

    public BigDecimal getAmountReceivable() {
        return amountReceivable;
    }

    public void setAmountReceivable(BigDecimal amountReceivable) {
        this.amountReceivable = money(amountReceivable, "Cte.amountReceivable");
    }

    public BigDecimal getCargoValue() {
        return cargoValue;
    }

    public void setCargoValue(BigDecimal cargoValue) {
        this.cargoValue = money(cargoValue, "Cte.cargoValue");
    }

    public CteIcms getIcms() {
        return icms;
    }

    public void setIcms(CteIcms icms) {
        this.icms = icms;
    }

    public CteParticipation getParticipation() {
        return participation;
    }
}
