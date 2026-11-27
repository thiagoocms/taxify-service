package br.com.cshub.taxify.config;

import br.com.cshub.taxify.enumerated.PendencyType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resumo diário das pendências ALTA por e-mail (spec 2026-11-27, seção 6), {@code taxify.pendency-alerts.*} no
 * application.yaml.
 */
@Component
@ConfigurationProperties(prefix = "taxify.pendency-alerts")
public class PendencyAlertProperties {

    /** TAXIFY_PENDENCY_ALERTS_ENABLED - liga o resumo (AV-RN-13). Na EC2 só depois da saída do sandbox do SES. */
    private boolean enabled = false;
    /** TAXIFY_PENDENCY_ALERT_TYPES - tipos que entram no aviso; vazio = todos (AV-RN-01). */
    private Set<PendencyType> types = EnumSet.noneOf(PendencyType.class);
    /** TAXIFY_PENDENCY_ALERT_DEFAULT_ON - aviso ligado para quem nunca configurou (AV-RN-08). */
    private boolean defaultOn = true;
    /** TAXIFY_PENDENCY_ALERT_MAX_PER_DAY - teto global de resumos por dia (AV-RN-09). */
    private int maxPerDay = 200;
    /** TAXIFY_PENDENCY_ALERT_MAX_COMPANIES_LISTED - empresas listadas no corpo (AV-RN-06). */
    private int maxCompaniesListed = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<PendencyType> getTypes() {
        return types;
    }

    /** Aceita a lista do ambiente (vírgula) - valor desconhecido derruba a subida, para não avisar o tipo errado. */
    public void setTypes(List<String> types) {
        EnumSet<PendencyType> parsed = EnumSet.noneOf(PendencyType.class);
        if (types != null) {
            for (String value : types) {
                if (value != null && !value.isBlank()) {
                    parsed.add(PendencyType.valueOf(value.strip().toUpperCase(Locale.ROOT)));
                }
            }
        }
        this.types = parsed;
    }

    /** Vazio = todos os tipos. */
    public boolean includes(PendencyType type) {
        return types.isEmpty() || types.contains(type);
    }

    public boolean isDefaultOn() {
        return defaultOn;
    }

    public void setDefaultOn(boolean defaultOn) {
        this.defaultOn = defaultOn;
    }

    public int getMaxPerDay() {
        return maxPerDay;
    }

    public void setMaxPerDay(int maxPerDay) {
        this.maxPerDay = maxPerDay;
    }

    public int getMaxCompaniesListed() {
        return maxCompaniesListed;
    }

    public void setMaxCompaniesListed(int maxCompaniesListed) {
        this.maxCompaniesListed = maxCompaniesListed;
    }
}
