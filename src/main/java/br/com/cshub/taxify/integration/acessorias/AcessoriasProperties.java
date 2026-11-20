package br.com.cshub.taxify.integration.acessorias;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings for the Acessórias company import. The API token itself is per firm
 * ({@link br.com.cshub.taxify.domain.accountingfirm.AccountingFirm#getAcessoriasApiToken()}),
 * not configured here - each accounting firm has its own Acessórias account.
 */
@Component
@ConfigurationProperties(prefix = "acessorias")
public class AcessoriasProperties {

    private String baseUrl = "https://api.acessorias.com";
    private boolean syncEnabled = true;
    private String syncCron = "0 0 3 * * *";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public String getSyncCron() {
        return syncCron;
    }

    public void setSyncCron(String syncCron) {
        this.syncCron = syncCron;
    }
}
