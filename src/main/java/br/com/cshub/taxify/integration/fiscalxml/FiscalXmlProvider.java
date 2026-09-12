package br.com.cshub.taxify.integration.fiscalxml;

/**
 * Strategy contract for a fiscal-XML source that {@link br.com.cshub.taxify.service.FiscalXmlSyncScheduler}
 * can pull from. Which implementation runs is chosen by bean name via
 * {@code fiscal-xml.sync.provider} (see {@link FiscalXmlSyncProperties#getProvider()}) - the
 * scheduler never references a specific provider directly, so adding a new source (besides
 * SIEG) only means registering another {@code @Component("<key>")} implementing this.
 */
public interface FiscalXmlProvider {

    FiscalXmlSyncResult sync(FiscalXmlSyncRequest request);
}
