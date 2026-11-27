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
