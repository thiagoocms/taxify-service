package br.com.cshub.taxify.integration.sieg;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sieg")
public class SiegProperties {

    private String baseUrl = "https://api.sieg.com";
    private String clientId;
    private String clientSecret;
    private String tokenUrl;
    private int maxRequestsPerMinute = 2;
    /**
     * TAXIFY_SIEG_EVENTS_ONLY_RECENT_NOTES - the daily event sweep of a side only asks SIEG when the database has a note
     * of that side issued in the sweep's window (a local query); false = every side, every day (the old behavior).
     */
    private boolean eventsOnlyWithRecentNotes = true;
    private final RequestLog requestLog = new RequestLog();

    /** The once-a-day log line of this instance's SIEG requests by purpose (SiegRequestMeter). */
    public static class RequestLog {
        /** TAXIFY_SIEG_REQUEST_LOG_ENABLED */
        private boolean enabled = true;
        /** TAXIFY_SIEG_REQUEST_LOG_CRON (America/Recife) */
        private String cron = "0 59 23 * * *";

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
    }

    public boolean isEventsOnlyWithRecentNotes() {
        return eventsOnlyWithRecentNotes;
    }

    public void setEventsOnlyWithRecentNotes(boolean eventsOnlyWithRecentNotes) {
        this.eventsOnlyWithRecentNotes = eventsOnlyWithRecentNotes;
    }

    public RequestLog getRequestLog() {
        return requestLog;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getTokenUrl() {
        return tokenUrl;
    }

    public void setTokenUrl(String tokenUrl) {
        this.tokenUrl = tokenUrl;
    }

    public int getMaxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }

    public void setMaxRequestsPerMinute(int maxRequestsPerMinute) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
    }
}
