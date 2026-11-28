package br.com.cshub.taxify.domain.fiscaldocument;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * An item ({@code det}) of a goods invoice - only {@link GoodsInvoice} has items (FD-RN-06, I-16). Part of the invoice
 * aggregate: no auditing columns and no company, as today. The covering index {@code (document_id, cfop, total_amount)}
 * serves the assessment and the stock credit (spec 3.6).
 */
@Entity
@Table(name = "fiscal_document_items",
        indexes = @Index(name = "idx_fdi_document_cfop_amount", columnList = "document_id, cfop, total_amount"))
public class FiscalDocumentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, foreignKey = @ForeignKey(name = "fk_fiscal_document_items_document"))
    private GoodsInvoice document;

    @Column(name = "item_number")
    private Integer itemNumber;

    @Embedded
    private Product product;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "unit", column = @Column(name = "commercial_unit", length = 10)),
            @AttributeOverride(name = "quantity", column = @Column(name = "commercial_quantity", precision = 19, scale = 4)),
            @AttributeOverride(name = "unitPrice", column = @Column(name = "commercial_unit_price", precision = 19, scale = 4))
    })
    private TradeUnit commercial;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "unit", column = @Column(name = "taxable_unit", length = 10)),
            @AttributeOverride(name = "quantity", column = @Column(name = "taxable_quantity", precision = 19, scale = 4)),
            @AttributeOverride(name = "unitPrice", column = @Column(name = "taxable_unit_price", precision = 19, scale = 4))
    })
    private TradeUnit taxable;

    @Column(name = "total_amount", precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Embedded
    private ItemIcms icms;

    @Embedded
    private ItemIcmsSt icmsSt;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "cst", column = @Column(name = "ipi_cst", length = 5)),
            @AttributeOverride(name = "base", column = @Column(name = "ipi_base", precision = 19, scale = 2)),
            @AttributeOverride(name = "rate", column = @Column(name = "ipi_rate", precision = 9, scale = 4)),
            @AttributeOverride(name = "amount", column = @Column(name = "ipi_amount", precision = 19, scale = 2))
    })
    private ItemFederalTax ipi;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "cst", column = @Column(name = "pis_cst", length = 5)),
            @AttributeOverride(name = "base", column = @Column(name = "pis_base", precision = 19, scale = 2)),
            @AttributeOverride(name = "rate", column = @Column(name = "pis_rate", precision = 9, scale = 4)),
            @AttributeOverride(name = "amount", column = @Column(name = "pis_amount", precision = 19, scale = 2))
    })
    private ItemFederalTax pis;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "cst", column = @Column(name = "cofins_cst", length = 5)),
            @AttributeOverride(name = "base", column = @Column(name = "cofins_base", precision = 19, scale = 2)),
            @AttributeOverride(name = "rate", column = @Column(name = "cofins_rate", precision = 9, scale = 4)),
            @AttributeOverride(name = "amount", column = @Column(name = "cofins_amount", precision = 19, scale = 2))
    })
    private ItemFederalTax cofins;

    @Embedded
    private ItemIbsCbs ibsCbs;

    @Embedded
    private SimplesCredit simplesCredit;

    protected FiscalDocumentItem() {
    }

    public FiscalDocumentItem(Integer itemNumber, Product product) {
        this.itemNumber = itemNumber;
        this.product = product;
    }

    /** Called by {@link GoodsInvoice#addItem(FiscalDocumentItem)}: an item belongs to one invoice only. */
    void attachTo(GoodsInvoice invoice) {
        if (document != null && document != invoice) {
            throw new IllegalStateException("item already belongs to another invoice");
        }
        this.document = Objects.requireNonNull(invoice, "invoice");
    }

    /**
     * Whether the item has any of the tax detail fields (origin, CSOSN, base modality and reduction, relief, ICMS-ST,
     * IPI/PIS/COFINS base and rate, CEST, cBenef, Simples credit) - the item of a note read by a parser before they
     * existed has none, and the checks skip what they need from them (the old {@code taxDetail == null}).
     */
    public boolean hasTaxDetail() {
        Product p = getProduct();
        ItemIcms i = getIcms();
        ItemIcmsSt st = getIcmsSt();
        SimplesCredit sn = getSimplesCredit();
        return anyNonNull(p.cest(), p.benefitCode(), i.origin(), i.csosn(), i.baseModality(), i.baseReduction(),
                i.reliefAmount(), st.baseModality(), st.mva(), st.base(), st.rate(), st.amount(),
                getIpi().base(), getIpi().rate(), getPis().base(), getPis().rate(), getCofins().base(), getCofins().rate(),
                sn.rate(), sn.amount());
    }

    private static boolean anyNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return true;
            }
        }
        return false;
    }

    /** The CFOP of the item, already without spaces. */
    public String cfop() {
        return product == null ? null : product.cfop();
    }

    public UUID getId() {
        return id;
    }

    /** Ids are kept when copying from the old table (FD-RN-20). */
    public void setId(UUID id) {
        this.id = id;
    }

    public GoodsInvoice getDocument() {
        return document;
    }

    public Integer getItemNumber() {
        return itemNumber;
    }

    /** Never null: {@link Product#EMPTY} when every column is null. */
    public Product getProduct() {
        return product == null ? Product.EMPTY : product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    /** Never null: {@link TradeUnit#EMPTY} when every column is null. */
    public TradeUnit getCommercial() {
        return commercial == null ? TradeUnit.EMPTY : commercial;
    }

    public void setCommercial(TradeUnit commercial) {
        this.commercial = commercial;
    }

    /** Never null: {@link TradeUnit#EMPTY} when every column is null. */
    public TradeUnit getTaxable() {
        return taxable == null ? TradeUnit.EMPTY : taxable;
    }

    public void setTaxable(TradeUnit taxable) {
        this.taxable = taxable;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = FiscalDecimals.money(totalAmount);
    }

    /** Never null: {@link ItemIcms#EMPTY} when every column is null. */
    public ItemIcms getIcms() {
        return icms == null ? ItemIcms.EMPTY : icms;
    }

    public void setIcms(ItemIcms icms) {
        this.icms = icms;
    }

    /** Never null: {@link ItemIcmsSt#EMPTY} when every column is null. */
    public ItemIcmsSt getIcmsSt() {
        return icmsSt == null ? ItemIcmsSt.EMPTY : icmsSt;
    }

    public void setIcmsSt(ItemIcmsSt icmsSt) {
        this.icmsSt = icmsSt;
    }

    /** Never null: {@link ItemFederalTax#EMPTY} when every column is null. */
    public ItemFederalTax getIpi() {
        return ipi == null ? ItemFederalTax.EMPTY : ipi;
    }

    public void setIpi(ItemFederalTax ipi) {
        this.ipi = ipi;
    }

    /** Never null: {@link ItemFederalTax#EMPTY} when every column is null. */
    public ItemFederalTax getPis() {
        return pis == null ? ItemFederalTax.EMPTY : pis;
    }

    public void setPis(ItemFederalTax pis) {
        this.pis = pis;
    }

    /** Never null: {@link ItemFederalTax#EMPTY} when every column is null. */
    public ItemFederalTax getCofins() {
        return cofins == null ? ItemFederalTax.EMPTY : cofins;
    }

    public void setCofins(ItemFederalTax cofins) {
        this.cofins = cofins;
    }

    public ItemIbsCbs getIbsCbs() {
        return ibsCbs;
    }

    public void setIbsCbs(ItemIbsCbs ibsCbs) {
        this.ibsCbs = ibsCbs;
    }

    /** Never null: {@link SimplesCredit#EMPTY} when every column is null. */
    public SimplesCredit getSimplesCredit() {
        return simplesCredit == null ? SimplesCredit.EMPTY : simplesCredit;
    }

    public void setSimplesCredit(SimplesCredit simplesCredit) {
        this.simplesCredit = simplesCredit;
    }
}
