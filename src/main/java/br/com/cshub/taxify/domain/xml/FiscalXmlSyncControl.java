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
 * One row per company, tracking sync progress: how many NFe SIEG reports for the
 * current sync window ({@link #issueDateFrom}/{@link #issueDateTo}, always the
 * whole current month - see {@link br.com.cshub.taxify.service.FiscalXmlSyncScheduler}),
 * updated by {@link br.com.cshub.taxify.integration.sieg.SiegClient#countXmls},
 * against how many are actually persisted for that company. Updated on every
 * {@link br.com.cshub.taxify.service.FiscalXmlIntegrationService#syncFromSieg} call -
 * not written to when the contar-xmls check itself fails, so totalSieg only ever
 * reflects a real answer from SIEG, never a guess.
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

    @Column(name = "data_emissao_inicio")
    private LocalDate issueDateFrom;

    @Column(name = "data_emissao_fim")
    private LocalDate issueDateTo;

    @Column(name = "total_sieg", nullable = false)
    private int totalSieg;

    @Column(name = "total_importado", nullable = false)
    private int totalImported;

    @Column(name = "ultima_verificacao_em", nullable = false)
    private LocalDateTime lastCheckedAt;

    /*
     * The fields above track the RECEIVED side (SIEG "CNPJdest" - notes from suppliers); these
     * track the ISSUED side ("CNPJemit" - the company's own notes), counted and paged apart.
     * Nullable: rows created before outbound sync existed start empty.
     */
    @Column(name = "issued_total_sieg")
    private Integer issuedTotalSieg;

    @Column(name = "issued_total_imported")
    private Integer issuedTotalImported;

    @Column(name = "issued_checked_at")
    private LocalDateTime issuedCheckedAt;

    /**
     * Last time the scheduler picked this company (success, failure or skipped as up to date) - the queue order. Not
     * a verification: a brand-new row has {@code lastCheckedAt} filled on creation but was never checked.
     */
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    public LocalDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(LocalDateTime lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    /**
     * When both sides were really checked: the older of the two; null (never) while the issued side was never checked -
     * the received side's time is filled on the row's creation and does not count alone.
     */
    public LocalDateTime getVerifiedAt() {
        if (issuedCheckedAt == null || lastCheckedAt == null) {
            return null;
        }
        return issuedCheckedAt.isBefore(lastCheckedAt) ? issuedCheckedAt : lastCheckedAt;
    }

    /** Last day the recent notes' events (cancelamentos...) were re-read, per side - see FiscalXmlSyncScheduler. */
    @Column(name = "received_events_checked_on")
    private LocalDate receivedEventsCheckedOn;

    @Column(name = "issued_events_checked_on")
    private LocalDate issuedEventsCheckedOn;

    /** Day the company's NFS-e were last fetched from SIEG - once a day. */
    @Column(name = "service_invoices_checked_on")
    private LocalDate serviceInvoicesCheckedOn;

    /*
     * CP-RN-22 (painel da captura): como o último mês verificado de cada lado fechou - copiados em
     * FiscalXmlIntegrationService#updateSyncControl quando a janela passa para um mês novo, antes dos contadores do
     * lado serem trocados. yyyy-MM; nulos até a primeira virada.
     */
    @Column(name = "received_prev_month", length = 7, columnDefinition = "char(7)")
    private String receivedPrevMonth;

    @Column(name = "received_prev_total_sieg")
    private Integer receivedPrevTotalSieg;

    @Column(name = "received_prev_imported")
    private Integer receivedPrevImported;

    @Column(name = "issued_prev_month", length = 7, columnDefinition = "char(7)")
    private String issuedPrevMonth;

    @Column(name = "issued_prev_total_sieg")
    private Integer issuedPrevTotalSieg;

    @Column(name = "issued_prev_imported")
    private Integer issuedPrevImported;

    /*
     * B1: what was still missing (SIEG's total - what SIEG delivered) after a complete pass from the first page, and its
     * month (yyyy-MM) - the reconciliation only runs again when that month's difference grows. Panel: the side's notes of
     * the window in Taxify from any source, at the last check. Nulls until the first time (V85).
     */
    @Column(name = "received_known_gap_month", length = 7, columnDefinition = "char(7)")
    private String receivedKnownGapMonth;

    @Column(name = "received_known_gap")
    private Integer receivedKnownGap;

    @Column(name = "issued_known_gap_month", length = 7, columnDefinition = "char(7)")
    private String issuedKnownGapMonth;

    @Column(name = "issued_known_gap")
    private Integer issuedKnownGap;

    @Column(name = "received_stored_any_source")
    private Integer receivedStoredAnySource;

    @Column(name = "issued_stored_any_source")
    private Integer issuedStoredAnySource;

    @Column(name = "received_earlier_gap_month", length = 7, columnDefinition = "char(7)")
    private String receivedEarlierGapMonth;

    @Column(name = "received_earlier_gap")
    private Integer receivedEarlierGap;

    @Column(name = "issued_earlier_gap_month", length = 7, columnDefinition = "char(7)")
    private String issuedEarlierGapMonth;

    @Column(name = "issued_earlier_gap")
    private Integer issuedEarlierGap;

    /**
     * The difference a complete pass left on the side for {@code month} (yyyy-MM); 0 = none known. Two slots per side -
     * the current month and the previous one, checked in the first days of the month.
     */
    public int knownGap(boolean issued, String month) {
        if (month == null) {
            return 0;
        }
        if (month.equals(strip(issued ? issuedKnownGapMonth : receivedKnownGapMonth))) {
            return valueOf(issued ? issuedKnownGap : receivedKnownGap);
        }
        if (month.equals(strip(issued ? issuedEarlierGapMonth : receivedEarlierGapMonth))) {
            return valueOf(issued ? issuedEarlierGap : receivedEarlierGap);
        }
        return 0;
    }

    /** Keeps {@code gap} for {@code month}: in the slot that already holds the month, else in the one of the older month. */
    public void recordKnownGap(boolean issued, String month, int gap) {
        String first = strip(issued ? issuedKnownGapMonth : receivedKnownGapMonth);
        String second = strip(issued ? issuedEarlierGapMonth : receivedEarlierGapMonth);
        boolean inFirst = month.equals(first) || (!month.equals(second) && (first == null
                || (second != null && first.compareTo(second) < 0)));
        if (inFirst) {
            if (issued) {
                issuedKnownGapMonth = month;
                issuedKnownGap = gap;
            } else {
                receivedKnownGapMonth = month;
                receivedKnownGap = gap;
            }
        } else if (issued) {
            issuedEarlierGapMonth = month;
            issuedEarlierGap = gap;
        } else {
            receivedEarlierGapMonth = month;
            receivedEarlierGap = gap;
        }
    }

    /** How the previous month closed is refreshed when that month is checked again (first days of the month). */
    public void refreshPreviousMonth(boolean issued, String month, int totalSieg, int imported) {
        String closed = strip(issued ? issuedPrevMonth : receivedPrevMonth);
        if (!month.equals(closed)) {
            return;
        }
        if (issued) {
            issuedPrevTotalSieg = totalSieg;
            issuedPrevImported = imported;
        } else {
            receivedPrevTotalSieg = totalSieg;
            receivedPrevImported = imported;
        }
    }

    private static String strip(String month) {
        return month == null ? null : month.strip();
    }

    private static int valueOf(Integer value) {
        return value == null ? 0 : value;
    }

    public void setStoredAnySource(boolean issued, int stored) {
        if (issued) {
            this.issuedStoredAnySource = stored;
        } else {
            this.receivedStoredAnySource = stored;
        }
    }

    public Integer getReceivedStoredAnySource() {
        return receivedStoredAnySource;
    }

    public Integer getIssuedStoredAnySource() {
        return issuedStoredAnySource;
    }

    public String getReceivedPrevMonth() {
        return receivedPrevMonth;
    }

    public Integer getReceivedPrevTotalSieg() {
        return receivedPrevTotalSieg;
    }

    public Integer getReceivedPrevImported() {
        return receivedPrevImported;
    }

    public String getIssuedPrevMonth() {
        return issuedPrevMonth;
    }

    public Integer getIssuedPrevTotalSieg() {
        return issuedPrevTotalSieg;
    }

    public Integer getIssuedPrevImported() {
        return issuedPrevImported;
    }

    /** O lado recebido fechou {@code month} com {@code totalSieg} no SIEG e {@code imported} no Taxify. */
    public void closeReceivedMonth(String month, Integer totalSieg, Integer imported) {
        this.receivedPrevMonth = month;
        this.receivedPrevTotalSieg = totalSieg;
        this.receivedPrevImported = imported;
    }

    public void closeIssuedMonth(String month, Integer totalSieg, Integer imported) {
        this.issuedPrevMonth = month;
        this.issuedPrevTotalSieg = totalSieg;
        this.issuedPrevImported = imported;
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

    public LocalDate getIssueDateFrom() {
        return issueDateFrom;
    }

    public void setIssueDateFrom(LocalDate issueDateFrom) {
        this.issueDateFrom = issueDateFrom;
    }

    public LocalDate getIssueDateTo() {
        return issueDateTo;
    }

    public void setIssueDateTo(LocalDate issueDateTo) {
        this.issueDateTo = issueDateTo;
    }

    public int getTotalSieg() {
        return totalSieg;
    }

    public void setTotalSieg(int totalSieg) {
        this.totalSieg = totalSieg;
    }

    public int getTotalImported() {
        return totalImported;
    }

    public void setTotalImported(int totalImported) {
        this.totalImported = totalImported;
    }

    public LocalDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(LocalDateTime lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public Integer getIssuedTotalSieg() {
        return issuedTotalSieg;
    }

    public void setIssuedTotalSieg(Integer issuedTotalSieg) {
        this.issuedTotalSieg = issuedTotalSieg;
    }

    public Integer getIssuedTotalImported() {
        return issuedTotalImported;
    }

    public void setIssuedTotalImported(Integer issuedTotalImported) {
        this.issuedTotalImported = issuedTotalImported;
    }

    public LocalDateTime getIssuedCheckedAt() {
        return issuedCheckedAt;
    }

    public void setIssuedCheckedAt(LocalDateTime issuedCheckedAt) {
        this.issuedCheckedAt = issuedCheckedAt;
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
