package br.com.cshub.taxify.service.taxclearance;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The scheduler's keys moved to {@code efisco.tax-clearance.*}; the old {@code efisco.certidao.*} keys keep working. */
class TaxClearanceSchedulerPropertiesTest {

    private static String resolve(String placeholder, Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment.resolveRequiredPlaceholders(placeholder);
    }

    @Test
    void defaultsWhenNoKeyIsSet() {
        assertThat(resolve(TaxClearanceScheduler.ENABLED_PROPERTY, Map.of())).isEqualTo("true");
        assertThat(resolve(TaxClearanceScheduler.CRON_PROPERTY, Map.of())).isEqualTo("0 0 6 * * MON");
    }

    @Test
    void theOldKeysStillWork() {
        Map<String, Object> old = Map.of("efisco.certidao.enabled", "false", "efisco.certidao.cron", "0 0 7 * * TUE");
        assertThat(resolve(TaxClearanceScheduler.ENABLED_PROPERTY, old)).isEqualTo("false");
        assertThat(resolve(TaxClearanceScheduler.CRON_PROPERTY, old)).isEqualTo("0 0 7 * * TUE");
    }

    /** Production sets the switch by environment variable (EFISCO_CERTIDAO_ENABLED): it still reaches the new key. */
    @Test
    void theOldEnvironmentVariablesStillWork() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env",
                Map.of("EFISCO_CERTIDAO_ENABLED", "false", "EFISCO_CERTIDAO_CRON", "0 0 7 * * TUE")));
        assertThat(environment.resolveRequiredPlaceholders(TaxClearanceScheduler.ENABLED_PROPERTY)).isEqualTo("false");
        assertThat(environment.resolveRequiredPlaceholders(TaxClearanceScheduler.CRON_PROPERTY)).isEqualTo("0 0 7 * * TUE");

        StandardEnvironment both = new StandardEnvironment();
        both.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env",
                Map.of("EFISCO_CERTIDAO_ENABLED", "false", "EFISCO_TAX_CLEARANCE_ENABLED", "true")));
        assertThat(both.resolveRequiredPlaceholders(TaxClearanceScheduler.ENABLED_PROPERTY)).isEqualTo("true");
    }

    @Test
    void theNewKeysWinOverTheOldOnes() {
        Map<String, Object> both = Map.of("efisco.certidao.enabled", "false", "efisco.tax-clearance.enabled", "true",
                "efisco.certidao.cron", "0 0 7 * * TUE", "efisco.tax-clearance.cron", "0 0 8 * * WED");
        assertThat(resolve(TaxClearanceScheduler.ENABLED_PROPERTY, both)).isEqualTo("true");
        assertThat(resolve(TaxClearanceScheduler.CRON_PROPERTY, both)).isEqualTo("0 0 8 * * WED");
    }
}
