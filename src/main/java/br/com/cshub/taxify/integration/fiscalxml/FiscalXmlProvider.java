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
}
