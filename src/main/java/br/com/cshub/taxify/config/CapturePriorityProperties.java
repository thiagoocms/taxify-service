package br.com.cshub.taxify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * "Sincronizar agora" do painel da captura (spec 2026-11-27, CP-RN-15 a CP-RN-20), {@code taxify.capture-priority.*}
 * no application.yaml. Limites decididos pelo Tony (P-CP-04, P-CP-05).
 */
@Component
@ConfigurationProperties(prefix = "taxify.capture-priority")
public class CapturePriorityProperties {

    /** TAXIFY_CAPTURE_PRIORITY_ENABLED - liga o "Sincronizar agora". Desligado: 503 e nenhuma vaga de prioridade. */
    private boolean enabled = true;
    /** TAXIFY_CAPTURE_PRIORITY_SLOTS_PER_TICK - vagas do lote para os pedidos (no máximo batch-size − 1). */
    private int slotsPerTick = 1;
    /** TAXIFY_CAPTURE_PRIORITY_COOLDOWN_MINUTES - espera entre dois pedidos atendidos da mesma empresa. */
    private int cooldownMinutes = 60;
    /** TAXIFY_CAPTURE_PRIORITY_MAX_OPEN_PER_FIRM - pedidos abertos ao mesmo tempo por escritório. */
    private int maxOpenPerFirm = 5;
    /** TAXIFY_CAPTURE_PRIORITY_PER_FIRM_PER_DAY - pedidos por escritório no dia civil de Recife. */
    private int perFirmPerDay = 10;
    /** TAXIFY_CAPTURE_PRIORITY_EXPIRE_HOURS - pedido aberto há mais tempo que isso vira EXPIRED. */
    private int expireHours = 24;
    /** TAXIFY_CAPTURE_PRIORITY_RETENTION_DAYS - pedidos com requested_at mais antigo são apagados (CP-RN-20). */
    private int retentionDays = 90;
    /** TAXIFY_CAPTURE_PRIORITY_PURGE_ENABLED - liga a limpeza diária. */
    private boolean purgeEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSlotsPerTick() {
        return slotsPerTick;
    }

    public void setSlotsPerTick(int slotsPerTick) {
        this.slotsPerTick = slotsPerTick;
    }

    public int getCooldownMinutes() {
        return cooldownMinutes;
    }

    public void setCooldownMinutes(int cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
    }

    public int getMaxOpenPerFirm() {
        return maxOpenPerFirm;
    }

    public void setMaxOpenPerFirm(int maxOpenPerFirm) {
        this.maxOpenPerFirm = maxOpenPerFirm;
    }

    public int getPerFirmPerDay() {
        return perFirmPerDay;
    }

    public void setPerFirmPerDay(int perFirmPerDay) {
        this.perFirmPerDay = perFirmPerDay;
    }

    public int getExpireHours() {
        return expireHours;
    }

    public void setExpireHours(int expireHours) {
        this.expireHours = expireHours;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public boolean isPurgeEnabled() {
        return purgeEnabled;
    }

    public void setPurgeEnabled(boolean purgeEnabled) {
        this.purgeEnabled = purgeEnabled;
    }
}
