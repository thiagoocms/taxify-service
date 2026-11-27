package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.Column;
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
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An event of a fiscal document of any type (cancelamento, carta de correção, manifestação...) - FD-RN-07. Kept by
 * access key: an event that arrives before its document is not lost, it is linked (and applied) when the document of
 * the same company shows up. One per (company, access key, event type, sequence).
 */
@Entity
@Table(name = "fiscal_document_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_fiscal_document_events",
                columnNames = {"company_id", "access_key", "event_type", "sequence_number"}),
        indexes = @Index(name = "idx_fde_document", columnList = "document_id"))
public class FiscalDocumentEvent extends AbstractAuditingEntity {

    public static final int MAX_DESCRIPTION = 120;

    /**
     * Registered events that cancel the document: 110111 (cancelamento) and 110112 (cancelamento por substituição) of
     * NF-e, NFC-e and CT-e - the set of {@code FiscalEventData} today; 101101 and 105102 of the national NFS-e - the set
     * of {@code NfseAdnService} today.
     */
    public static final Set<String> CANCELLATION_TYPES = Set.of("110111", "110112", "101101", "105102");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, foreignKey = @ForeignKey(name = "fk_fiscal_document_events_company"))
    private Company company;

    /** Null while the document itself has not arrived. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", foreignKey = @ForeignKey(name = "fk_fiscal_document_events_document"))
    private FiscalDocument document;

    /** 44 digits (NF-e, NFC-e, CT-e) or 50 (national NFS-e). */
    @Column(name = "access_key", length = 80, nullable = false)
    private String accessKey;

    /** tpEvento. */
    @Column(name = "event_type", length = 6, nullable = false)
    private String eventType;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "description", length = MAX_DESCRIPTION)
    private String description;

    @Column(name = "event_at")
    private LocalDateTime eventAt;

    @Column(name = "protocol", length = 30)
    private String protocol;

    /** Justification (cancelamento) or correction (carta de correção). */
    @Column(name = "event_text", columnDefinition = "TEXT")
    private String text;

    /** The authority accepted the event - only registered events change the document. */
    @Column(name = "registered", nullable = false)
    private boolean registered;

    protected FiscalDocumentEvent() {
    }

    public FiscalDocumentEvent(Company company, String accessKey, String eventType, int sequenceNumber,
                               String description, LocalDateTime eventAt, String protocol, String text, boolean registered) {
        this.company = Objects.requireNonNull(company, "company");
        this.accessKey = requireText(accessKey, "accessKey");
        this.eventType = requireText(eventType, "eventType");
        this.sequenceNumber = sequenceNumber;
        this.description = truncate(description);
        this.eventAt = eventAt;
        this.protocol = protocol;
        this.text = text;
        this.registered = registered;
    }

    /** A registered cancellation (FD-RN-07): it cancels the document when linked. */
    public boolean isRegisteredCancellation() {
        return registered && CANCELLATION_TYPES.contains(eventType);
    }

    public boolean isLinked() {
        return document != null;
    }

    /**
     * Links the event to its document - same company and same access key, any document type - and applies it: a
     * registered cancellation cancels the document at the event time ({@link FiscalDocument#cancel(LocalDateTime)}).
     *
     * @return whether the document was canceled now
     */
    public boolean linkTo(FiscalDocument target) {
        Objects.requireNonNull(target, "target");
        if (!sameCompany(target) || !accessKey.equals(target.getAccessKey())) {
            throw new IllegalArgumentException("event and document of different company or access key");
        }
        this.document = target;
        return isRegisteredCancellation() && target.cancel(eventAt);
    }

    /** The link of a stored event, copied as it is - the effect on the document was already applied back then. */
    void restoreLink(FiscalDocument stored) {
        this.document = stored;
    }

    /** Copies the auditing columns of the row this event is a copy of (the old table), unchanged. */
    public void copyAuditingFrom(AbstractAuditingEntity source) {
        restoreAuditingFrom(source);
    }

    private boolean sameCompany(FiscalDocument target) {
        Company other = target.getCompany();
        if (other == company) {
            return true;
        }
        return other != null && company.getId() != null && company.getId().equals(other.getId());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_DESCRIPTION ? value : value.substring(0, MAX_DESCRIPTION);
    }

    public UUID getId() {
        return id;
    }

    /** Ids are kept when copying from the old table (FD-RN-20). */
    public void setId(UUID id) {
        this.id = id;
    }

    public Company getCompany() {
        return company;
    }

    public FiscalDocument getDocument() {
        return document;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getEventType() {
        return eventType;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public String getDescription() {
        return description;
    }

    public LocalDateTime getEventAt() {
        return eventAt;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getText() {
        return text;
    }

    public boolean isRegistered() {
        return registered;
    }
}
