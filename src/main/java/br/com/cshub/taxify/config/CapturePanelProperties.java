package br.com.cshub.taxify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Painel da captura de XML (spec 2026-11-27, seção 10), {@code taxify.capture-panel.*} no application.yaml. As janelas
 * de "em dia" e o limite de "fila parada" são decisões de produto (P-CP-03), não regra fiscal.
 */
@Component
@ConfigurationProperties(prefix = "taxify.capture-panel")
public class CapturePanelProperties {

    /** TAXIFY_CAPTURE_PANEL_SIEG_FRESH_HOURS - janela de "em dia" do canal SIEG (CP-RN-06). */
    private int siegFreshHours = 24;
    /** TAXIFY_CAPTURE_PANEL_DIRECT_FRESH_HOURS - janela de "em dia" da SEFAZ e do ADN (CP-RN-07). */
    private int directFreshHours = 3;
    /** TAXIFY_CAPTURE_PANEL_STALLED_MINUTES - fila SIEG "parada" (CP-RN-12). */
    private int stalledMinutes = 30;
    /** TAXIFY_CAPTURE_PANEL_MAX_PAGE_SIZE - tamanho máximo da página (CP-RN-14). */
    private int maxPageSize = 100;
    /**
     * TAXIFY_CAPTURE_PANEL_PLATFORM_ENABLED - liga a visão da plataforma (CP-RN-02). Desligada, as rotas
     * /platform/capture-panel/** respondem 404. Na EC2, só depois da política de privacidade e do acordo de operador
     * citarem a exceção à F1-RN-15, e com aprovação do Tony.
     */
    private boolean platformEnabled = false;

    public int getSiegFreshHours() {
        return siegFreshHours;
    }

    public void setSiegFreshHours(int siegFreshHours) {
        this.siegFreshHours = siegFreshHours;
    }

    public int getDirectFreshHours() {
        return directFreshHours;
    }

    public void setDirectFreshHours(int directFreshHours) {
        this.directFreshHours = directFreshHours;
    }

    public int getStalledMinutes() {
        return stalledMinutes;
    }

    public void setStalledMinutes(int stalledMinutes) {
        this.stalledMinutes = stalledMinutes;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public boolean isPlatformEnabled() {
        return platformEnabled;
    }

    public void setPlatformEnabled(boolean platformEnabled) {
        this.platformEnabled = platformEnabled;
    }
}
