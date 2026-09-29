package br.com.cshub.taxify.integration.fiscalxml;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import br.com.cshub.taxify.enumerated.FiscalXmlDirection;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Provider-agnostic settings for {@link br.com.cshub.taxify.service.FiscalXmlSyncScheduler}.
 * {@code provider} picks which {@link FiscalXmlProvider} bean (by name) the scheduler calls;
 * everything provider-specific (credentials, rate limits, storage path...) stays in that
 * provider's own properties (e.g. {@code SiegProperties}).
 *
 * <p>{@code batchSize} exists because the provider's own rate limit (e.g. SIEG's 2
 * requests/minute, account-wide - not per company) makes it impossible to sync every
 * company in one run once there are more companies than the limit allows per tick:
 * doing so would block the scheduler thread for minutes syncing everyone serially,
 * risking runs piling up past {@code cron}'s own interval. Instead, each tick only
 * syncs {@code batchSize} companies (round-robin, so every company eventually gets a
 * turn), and {@code cron} is meant to fire much more often than the old "everyone,
 * hourly" default - tune {@code batchSize} to whatever the active provider can take
 * per tick (e.g. batch-size 2 with a once-a-minute cron matches SIEG's 2 req/min).
 */
@Component
@ConfigurationProperties(prefix = "fiscal-xml.sync")
public class FiscalXmlSyncProperties {

    private boolean enabled = true;
    private String cron = "0 * * * * *";
    private String provider = "sieg";
    private int batchSize = 2;
    private LocalDate referenceDate;
    private String storagePath = "./data/fiscal-xml";
    /**
     * Which sides get synced. Each direction is a separate provider query (and a separate
     * control row), so enabling SAIDA roughly doubles the requests per company against the
     * provider's account-wide rate limit - the round-robin just takes twice as long to
     * cycle through everyone.
     */
    private Set<FiscalXmlDirection> directions = EnumSet.of(FiscalXmlDirection.ENTRADA, FiscalXmlDirection.SAIDA);

    public Set<FiscalXmlDirection> getDirections() {
        return directions;
    }

    public void setDirections(Set<FiscalXmlDirection> directions) {
        this.directions = directions;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public void setReferenceDate(LocalDate referenceDate) {
        this.referenceDate = referenceDate;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String xmlStoragePath) {
        this.storagePath = xmlStoragePath;
    }
}
