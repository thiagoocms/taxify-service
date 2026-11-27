package br.com.cshub.taxify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Stock credit survey (spec 2026-11-27, F5a, section 9), {@code taxify.reform.stock-survey.*} in application.yaml.
 * Born off: the routine and every {@code /reform/stock-credit/**} route answer only with {@code enabled=true}
 * (CE-RN-15) - switched on at the EC2 by Tony after the QA and the parallel run (V-5a-2).
 */
@Component
@ConfigurationProperties(prefix = "taxify.reform.stock-survey")
public class StockSurveyProperties {

    /** TAXIFY_STOCK_SURVEY_ENABLED - the routine and the routes (CE-RN-15). */
    private boolean enabled = false;
    /** TAXIFY_STOCK_SURVEY_REFRESH_COOLDOWN_MINUTES - one "refresh now" per company in this interval (CE-RN-09). */
    private int refreshCooldownMinutes = 10;
    /** TAXIFY_STOCK_SURVEY_STALE_MINUTES - an unfinished daily run older than this may be taken over (CE-RN-08 a). */
    private int staleMinutes = 120;
    /** TAXIFY_STOCK_SURVEY_BATCH_SIZE - companies per batch of the routine (CE-RN-07). */
    private int batchSize = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRefreshCooldownMinutes() {
        return refreshCooldownMinutes;
    }

    public void setRefreshCooldownMinutes(int refreshCooldownMinutes) {
        this.refreshCooldownMinutes = refreshCooldownMinutes;
    }

    public int getStaleMinutes() {
        return staleMinutes;
    }

    public void setStaleMinutes(int staleMinutes) {
        this.staleMinutes = staleMinutes;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
