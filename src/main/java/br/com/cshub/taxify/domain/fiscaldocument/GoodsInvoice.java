package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.FiscalDocumentInvariant;
import br.com.cshub.taxify.enumerated.InvariantScope;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A goods invoice - NF-e ({@link Nfe}) or NFC-e ({@link Nfce}): the only documents with items (FD-RN-06, I-16) and
 * with the block G columns. {@code from GoodsInvoice} brings both, which is what every query that filters
 * {@code document_type = 'NFE'} without the model does today (FD-RN-14).
 */
@Entity
public abstract class GoodsInvoice extends FiscalDocument {

    /** Legacy key label of both goods subtypes (FD-RN-21). */
    public static final String LEGACY_KEY_LABEL = "NFE";

    @Embedded
    private GoodsInvoiceHeader header;

    @Embedded
    private IssuerDetails issuerDetails;

    @Embedded
    private RecipientDetails recipientDetails;

    @Embedded
    private GoodsTotals goodsTotals;

    @Column(name = "rectified_file_path", length = 1000)
    private String rectifiedFilePath;

    @Column(name = "last_rectification_checked_at")
    private LocalDateTime lastRectificationCheckedAt;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("itemNumber ASC")
    private List<FiscalDocumentItem> items = new ArrayList<>();

    protected GoodsInvoice() {
    }

    protected GoodsInvoice(Company company, DocumentSourceKey sourceSystem) {
        super(company, sourceSystem);
    }

    /** The model this subtype requires (I-10, I-11). */
    protected abstract String requiredModel();

    /** The invariant that checks the model and key of this subtype. */
    protected abstract FiscalDocumentInvariant modelAndKeyInvariant();

    @Override
    public String legacyKeyLabel() {
        return LEGACY_KEY_LABEL;
    }

    /** Adds the item to this invoice (the item belongs to the aggregate: cascade and orphan removal). */
    public void addItem(FiscalDocumentItem item) {
        Objects.requireNonNull(item, "item");
        item.attachTo(this);
        items.add(item);
    }

    public List<FiscalDocumentItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * Whether a rectified XML may be produced: never for the company's own issue nor for a canceled invoice (the rule
     * of {@code FiscalXmlRectificationService} today).
     */
    public boolean isRectifiable() {
        return !isOwnIssue() && !isCanceled();
    }

    /** I-07: the odd file uploaded by hand may come without a key (identified by its id, {@link #accessKeyOrId()}). */
    @Override
    protected boolean requiresAccessKey() {
        return !getSourceSystem().isUpload();
    }

    @Override
    protected void checkInvariants(InvariantScope scope) {
        super.checkInvariants(scope);
        require(modelAndKeyInvariant(), scope, hasRequiredModelAndKey());
    }

    /**
     * I-10/I-11: the model of the subtype and a 44-digit key. Where I-07 lets the key be absent (upload), only the
     * model is required; a key that is present must still have 44 digits.
     */
    private boolean hasRequiredModelAndKey() {
        if (!requiredModel().equals(getModel())) {
            return false;
        }
        if (!hasText(getAccessKey())) {
            return !requiresAccessKey();
        }
        return isDigitsOfLength(getAccessKey(), ACCESS_KEY_44);
    }

    public GoodsInvoiceHeader getHeader() {
        return header;
    }

    public void setHeader(GoodsInvoiceHeader header) {
        this.header = header;
    }

    public IssuerDetails getIssuerDetails() {
        return issuerDetails;
    }

    public void setIssuerDetails(IssuerDetails issuerDetails) {
        this.issuerDetails = issuerDetails;
    }

    public RecipientDetails getRecipientDetails() {
        return recipientDetails;
    }

    public void setRecipientDetails(RecipientDetails recipientDetails) {
        this.recipientDetails = recipientDetails;
    }

    public GoodsTotals getGoodsTotals() {
        return goodsTotals;
    }

    public void setGoodsTotals(GoodsTotals goodsTotals) {
        this.goodsTotals = goodsTotals;
    }

    public String getRectifiedFilePath() {
        return rectifiedFilePath;
    }

    public void setRectifiedFilePath(String rectifiedFilePath) {
        this.rectifiedFilePath = rectifiedFilePath;
    }

    public LocalDateTime getLastRectificationCheckedAt() {
        return lastRectificationCheckedAt;
    }

    public void setLastRectificationCheckedAt(LocalDateTime lastRectificationCheckedAt) {
        this.lastRectificationCheckedAt = lastRectificationCheckedAt;
    }
}
