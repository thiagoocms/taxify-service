package br.com.cshub.taxify.integration.fiscalxml;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for {@link br.com.cshub.taxify.service.FiscalXmlRectificationScheduler} -
 * the routine that automatically applies {@link br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule}s
 * to recently synced notes, on top of the {@link FiscalXmlSyncProperties}-driven download.
 */
@Component
@ConfigurationProperties(prefix = "fiscal-xml.rectification")
public class FiscalXmlRectificationProperties {

    private boolean enabled = true;
    private String cron = "0 30 * * * *";
    private int lookbackDays = 1;

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

    public int getLookbackDays() {
        return lookbackDays;
    }

    public void setLookbackDays(int lookbackDays) {
        this.lookbackDays = lookbackDays;
    }
}
