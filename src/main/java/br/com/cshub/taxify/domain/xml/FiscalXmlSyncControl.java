package br.com.cshub.taxify.domain.xml;

import br.com.cshub.taxify.domain.AbstractAuditingEntity;
import br.com.cshub.taxify.domain.company.Company;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row per company in the provider's capture queue: when the scheduler last picked it (the queue order and the atomic
 * reservation) and the days of the routines that run once a day (events per side, NFS-e). The counters of the capture
 * (what the provider counted and what was imported, per side and month, the window and the known gaps) are in
 * {@code capture_period_counts} ({@link br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount}, FD-RN-32); the
 * columns that held them here were dropped by V89.
 */
@Entity
@Table(name = "fiscal_xml_sync_control", uniqueConstraints = @UniqueConstraint(columnNames = "company_id"))
public class FiscalXmlSyncControl extends AbstractAuditingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /**
     * Last time the scheduler picked this company (success, failure or skipped as up to date) - the queue order. Not
     * a verification.
     */
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /** Last day the recent notes' events (cancelamentos...) were re-read, per side - see FiscalXmlSyncScheduler. */
    @Column(name = "received_events_checked_on")
    private LocalDate receivedEventsCheckedOn;

    @Column(name = "issued_events_checked_on")
    private LocalDate issuedEventsCheckedOn;

    /** Day the company's NFS-e were last fetched from SIEG - once a day. */
    @Column(name = "service_invoices_checked_on")
    private LocalDate serviceInvoicesCheckedOn;

    public LocalDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(LocalDateTime lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

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

    public LocalDate getReceivedEventsCheckedOn() {
        return receivedEventsCheckedOn;
    }

    public void setReceivedEventsCheckedOn(LocalDate receivedEventsCheckedOn) {
        this.receivedEventsCheckedOn = receivedEventsCheckedOn;
    }

    public LocalDate getIssuedEventsCheckedOn() {
        return issuedEventsCheckedOn;
    }

    public void setIssuedEventsCheckedOn(LocalDate issuedEventsCheckedOn) {
        this.issuedEventsCheckedOn = issuedEventsCheckedOn;
    }

    public LocalDate getServiceInvoicesCheckedOn() {
        return serviceInvoicesCheckedOn;
    }

    public void setServiceInvoicesCheckedOn(LocalDate serviceInvoicesCheckedOn) {
        this.serviceInvoicesCheckedOn = serviceInvoicesCheckedOn;
    }
}
