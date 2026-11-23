package br.com.cshub.taxify.integration.nfse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * NFS-e capture from the Ambiente Nacional (ADN, Sistema Nacional NFS-e) - the "contribuintes"
 * distribution API, with each company's A1 (mutual TLS). Runs for companies with a certificate and,
 * on their plan, the fiscal XML import with the national NFS-e integration.
 */
@Component
@ConfigurationProperties(prefix = "nfse.adn")
public class AdnProperties {

    private boolean enabled = true;
    /** Hourly, after the SEFAZ capture (:15) and before the pendencies (:40). */
    private String cron = "0 25 * * * *";
    /** false = produção restrita (the national test environment). */
    private boolean production = true;
    private String url = "https://adn.nfse.gov.br/contribuintes";
    private String homologationUrl = "https://adn.producaorestrita.nfse.gov.br/contribuintes";
    /** Batches (lotes) per company per run - bounds one company's turn. */
    private int maxBatchesPerCompany = 20;
    private long pauseBetweenBatchesMillis = 1000;
    private int timeoutSeconds = 60;
    /** Nothing new: don't ask that company again for this long. */
    private int waitMinutesWhenCaughtUp = 60;

    public String endpoint() {
        return production ? url : homologationUrl;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public boolean isProduction() { return production; }
    public void setProduction(boolean production) { this.production = production; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getHomologationUrl() { return homologationUrl; }
    public void setHomologationUrl(String homologationUrl) { this.homologationUrl = homologationUrl; }
    public int getMaxBatchesPerCompany() { return maxBatchesPerCompany; }
    public void setMaxBatchesPerCompany(int maxBatchesPerCompany) { this.maxBatchesPerCompany = maxBatchesPerCompany; }
    public long getPauseBetweenBatchesMillis() { return pauseBetweenBatchesMillis; }
    public void setPauseBetweenBatchesMillis(long pauseBetweenBatchesMillis) { this.pauseBetweenBatchesMillis = pauseBetweenBatchesMillis; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getWaitMinutesWhenCaughtUp() { return waitMinutesWhenCaughtUp; }
    public void setWaitMinutesWhenCaughtUp(int waitMinutesWhenCaughtUp) { this.waitMinutesWhenCaughtUp = waitMinutesWhenCaughtUp; }
}
