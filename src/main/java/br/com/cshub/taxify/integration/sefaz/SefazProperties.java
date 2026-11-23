package br.com.cshub.taxify.integration.sefaz;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SEFAZ distribution (NFeDistribuicaoDFe) - XML capture straight from the Ambiente Nacional
 * with each company's own A1 certificate. Runs only for companies with a certificate
 * uploaded (or one of the same CNPJ root) and the fiscal XML import service on their plan.
 */
@Component
@ConfigurationProperties(prefix = "sefaz.dist")
public class SefazProperties {

    private boolean enabled = true;
    /** Hourly: SEFAZ wants an hour between queries once a company is caught up, and keeps notes for 90 days only. */
    private String cron = "0 15 * * * *";
    /** false = homologação (test environment, tpAmb 2). */
    private boolean production = true;
    private String url = "https://www1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx";
    private String homologationUrl = "https://hom1.nfe.fazenda.gov.br/NFeDistribuicaoDFe/NFeDistribuicaoDFe.asmx";
    /** Up to 50 documents each - caps one company's turn per run. */
    private int maxBatchesPerCompany = 40;
    private long pauseBetweenBatchesMillis = 2000;
    private int timeoutSeconds = 90;
    /** NFeRecepcaoEvento4 of the Ambiente Nacional - where the Ciência da Operação goes. */
    private String eventUrl = "https://www.nfe.fazenda.gov.br/NFeRecepcaoEvento4/NFeRecepcaoEvento4.asmx";
    private String eventHomologationUrl = "https://hom1.nfe.fazenda.gov.br/NFeRecepcaoEvento4/NFeRecepcaoEvento4.asmx";
    /** Sends the Ciência da Operação for every summary (resNFe) so SEFAZ releases the full XML. */
    private boolean autoCiencia = true;
    /** Ciências per company per run (in batches of 20). */
    private int cienciaPerRun = 100;
    /** A refused ciência is tried again this many times in all, then left for a person. */
    private int cienciaMaxAttempts = 3;
    /** Full notes asked by key (consChNFe) per company per run, after the ciência. */
    private int fetchPerRun = 20;
    /** UFs whose companies are captured automatically - starting with PE; empty = every UF. */
    private java.util.List<String> ufs = java.util.List.of("PE");

    /** Whether a company of this UF is in the automatic capture. */
    public boolean coversUf(String uf) {
        if (ufs == null || ufs.isEmpty()) {
            return true;
        }
        return uf != null && ufs.stream().anyMatch(item -> item.strip().equalsIgnoreCase(uf.strip()));
    }

    public java.util.List<String> getUfs() {
        return ufs;
    }

    public void setUfs(java.util.List<String> ufs) {
        this.ufs = ufs;
    }

    public String endpoint() {
        return production ? url : homologationUrl;
    }

    public String eventEndpoint() {
        return production ? eventUrl : eventHomologationUrl;
    }

    public String getEventUrl() {
        return eventUrl;
    }

    public void setEventUrl(String eventUrl) {
        this.eventUrl = eventUrl;
    }

    public String getEventHomologationUrl() {
        return eventHomologationUrl;
    }

    public void setEventHomologationUrl(String eventHomologationUrl) {
        this.eventHomologationUrl = eventHomologationUrl;
    }

    public boolean isAutoCiencia() {
        return autoCiencia;
    }

    public void setAutoCiencia(boolean autoCiencia) {
        this.autoCiencia = autoCiencia;
    }

    public int getCienciaPerRun() {
        return cienciaPerRun;
    }

    public void setCienciaPerRun(int cienciaPerRun) {
        this.cienciaPerRun = cienciaPerRun;
    }

    public int getCienciaMaxAttempts() {
        return cienciaMaxAttempts;
    }

    public void setCienciaMaxAttempts(int cienciaMaxAttempts) {
        this.cienciaMaxAttempts = cienciaMaxAttempts;
    }

    public int getFetchPerRun() {
        return fetchPerRun;
    }

    public void setFetchPerRun(int fetchPerRun) {
        this.fetchPerRun = fetchPerRun;
    }

    public String tpAmb() {
        return production ? "1" : "2";
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

    public boolean isProduction() {
        return production;
    }

    public void setProduction(boolean production) {
        this.production = production;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getHomologationUrl() {
        return homologationUrl;
    }

    public void setHomologationUrl(String homologationUrl) {
        this.homologationUrl = homologationUrl;
    }

    public int getMaxBatchesPerCompany() {
        return maxBatchesPerCompany;
    }

    public void setMaxBatchesPerCompany(int maxBatchesPerCompany) {
        this.maxBatchesPerCompany = maxBatchesPerCompany;
    }

    public long getPauseBetweenBatchesMillis() {
        return pauseBetweenBatchesMillis;
    }

    public void setPauseBetweenBatchesMillis(long pauseBetweenBatchesMillis) {
        this.pauseBetweenBatchesMillis = pauseBetweenBatchesMillis;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
