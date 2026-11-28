package legacy.xml;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
import java.util.UUID;

/**
 * A SEFAZ event of one of the company's notes (cancelamento, carta de correção,
 * manifestação...). Kept by access key, so an event that arrives before its note is not
 * lost: it's linked (and applied) when the note shows up - see FiscalXmlEventService.
 */
@Entity(name = "LegacyFiscalXmlEvent")
@Table(name = "fiscal_xml_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_fiscal_xml_event",
                columnNames = {"company_id", "access_key", "event_type", "sequence_number"}),
        indexes = @Index(name = "idx_fiscal_xml_event_key", columnList = "company_id, access_key"))
public class FiscalXmlEvent extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** Null while the note itself hasn't been imported yet. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private FiscalXmlDocument document;

    @Column(name = "access_key", length = 44, nullable = false)
    private String accessKey;

    /** tpEvento: 110111 cancelamento, 110110 carta de correção, 210200 confirmação da operação... */
    @Column(name = "event_type", length = 6, nullable = false)
    private String eventType;

    @Column(name = "description", length = 120)
    private String description;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "event_at")
    private LocalDateTime eventAt;

    @Column(name = "protocol", length = 30)
    private String protocol;

    /** Justification (cancelamento) or correction (carta de correção). */
    @Column(name = "event_text", columnDefinition = "TEXT")
    private String text;

    /** SEFAZ accepted the event - only registered events change the note. */
    @Column(name = "registered", nullable = false)
    private boolean registered;

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

    public FiscalXmlDocument getDocument() {
        return document;
    }

    public void setDocument(FiscalXmlDocument document) {
        this.document = document;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public LocalDateTime getEventAt() {
        return eventAt;
    }

    public void setEventAt(LocalDateTime eventAt) {
        this.eventAt = eventAt;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public boolean isRegistered() {
        return registered;
    }

    public void setRegistered(boolean registered) {
        this.registered = registered;
    }
}
