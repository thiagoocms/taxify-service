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

    public Product getProduct() {
        return product;
    }

    public void setProduct(Product product) {
        this.product = product;
    }

    public TradeUnit getCommercial() {
        return commercial;
    }

    public void setCommercial(TradeUnit commercial) {
        this.commercial = commercial;
    }

    public TradeUnit getTaxable() {
        return taxable;
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

    public ItemIcms getIcms() {
        return icms;
    }

    public void setIcms(ItemIcms icms) {
        this.icms = icms;
    }

    public ItemIcmsSt getIcmsSt() {
        return icmsSt;
    }

    public void setIcmsSt(ItemIcmsSt icmsSt) {
        this.icmsSt = icmsSt;
    }

    public ItemFederalTax getIpi() {
        return ipi;
    }

    public void setIpi(ItemFederalTax ipi) {
        this.ipi = ipi;
    }

    public ItemFederalTax getPis() {
        return pis;
    }

    public void setPis(ItemFederalTax pis) {
        this.pis = pis;
    }

    public ItemFederalTax getCofins() {
        return cofins;
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

    public SimplesCredit getSimplesCredit() {
        return simplesCredit;
    }

    public void setSimplesCredit(SimplesCredit simplesCredit) {
        this.simplesCredit = simplesCredit;
    }
}
