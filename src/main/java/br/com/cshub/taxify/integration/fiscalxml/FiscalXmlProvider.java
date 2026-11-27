package br.com.cshub.taxify.integration.fiscalxml;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Strategy contract for a fiscal-XML source that {@link br.com.cshub.taxify.service.FiscalXmlSyncScheduler}
 * can pull from. Which implementation runs is chosen by bean name via
 * {@code fiscal-xml.sync.provider} (see {@link FiscalXmlSyncProperties#getProvider()}) - the
 * scheduler never references a specific provider directly, so adding a new source (besides
 * SIEG) only means registering another {@code @Component("<key>")} implementing this.
 */
public interface FiscalXmlProvider {

    FiscalXmlSyncResult sync(FiscalXmlSyncRequest request);

    /**
     * Re-reads one side's notes issued in [from, to] only for their events (a note canceled
     * after it was imported). Providers without events do nothing.
     *
     * @return new events stored
     */
    default int refreshEvents(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        return 0;
    }

    /**
     * The company's service invoices (NFS-e) issued in [from, to], provided and taken. Providers
     * without NFS-e do nothing.
     *
     * @return new notes stored
     */
    default int syncServiceInvoices(UUID companyId, LocalDate from, LocalDate to) {
        return 0;
    }

    /**
     * The company's service invoices (NFS-e) issued in one window [from, to] of at most a month, downloaded only when the
     * provider's count is above what it already delivered (A1). {@code issuerCount}/{@code recipientCount}: the provider's
     * NFS-e counts with the company as issuer / as recipient, when already known from this attempt's NF-e counts of the
     * same window; null = unknown, the provider counts by itself. Providers without a count download the window.
     *
     * @return new notes stored
     */
    default int syncServiceInvoicesIfMissing(UUID companyId, LocalDate from, LocalDate to, Integer issuerCount,
                                             Integer recipientCount) {
        return syncServiceInvoices(companyId, from, to);
    }

    /**
     * The count gate in shadow (A1): the service invoices of [from, to] are downloaded as without the gate, and the
     * current month's counts already known from this attempt are compared with what the provider had delivered - only
     * logged. Providers without a count just download.
     *
     * @return new notes stored
     */
    default int syncServiceInvoicesShadow(UUID companyId, LocalDate from, LocalDate to, Integer issuerCount,
                                          Integer recipientCount) {
        return syncServiceInvoices(companyId, from, to);
    }

    /**
     * The side's notes of the previous month [from, to], checked again in the first days of the month: only what the
     * provider counts beyond what it already delivered is downloaded. Providers without it do nothing (null).
     */
    default FiscalXmlSyncResult checkPreviousMonth(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        return null;
    }

    /** One page of a past period's notes, outside the regular bookkeeping. {@code lastPage}: nothing more to ask. */
    record RecoveryPage(int notes, int persistedNew, boolean lastPage) {
    }

    /**
     * Recovery of a blocked period (QA-02): page {@code page} of the side's notes issued in [from, to]. Empty = this
     * provider has no recovery (the recovery skips it).
     */
    default java.util.Optional<RecoveryPage> recoverPage(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to, int page) {
        return java.util.Optional.empty();
    }
}
