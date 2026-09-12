package br.com.cshub.taxify.integration.fiscalxml;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for {@link br.com.cshub.taxify.service.FiscalXmlPurgeScheduler} - deletes the
 * physical XML files (original and rectified copies) of notes older than {@code
 * retentionMonths}, freeing disk space. Only the files on disk are removed; the
 * extracted field values in {@link br.com.cshub.taxify.domain.xml.FiscalXmlDocument}'s
 * own columns stay in the database either way, so historical reporting keeps working -
 * only re-exporting the raw XML of a purged note stops being possible.
 */
@Component
@ConfigurationProperties(prefix = "fiscal-xml.purge")
public class FiscalXmlPurgeProperties {

    private boolean enabled = true;
    private String cron = "0 0 4 1 * *";
    private int retentionMonths = 3;

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

    public int getRetentionMonths() {
        return retentionMonths;
    }

    public void setRetentionMonths(int retentionMonths) {
        this.retentionMonths = retentionMonths;
    }
}
