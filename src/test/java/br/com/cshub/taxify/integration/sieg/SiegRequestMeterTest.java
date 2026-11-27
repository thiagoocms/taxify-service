package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.integration.fiscalxml.ProviderCallContext;
import br.com.cshub.taxify.integration.fiscalxml.ProviderCallPurpose;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** A5: every SIEG request counted by purpose - the Micrometer counter and the daily summary (counts only). */
class SiegRequestMeterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private SiegRequestMeter meter() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("registry", registry);
        ObjectProvider<MeterRegistry> provider = beans.getBeanProvider(MeterRegistry.class);
        return new SiegRequestMeter(new SiegProperties(), provider);
    }

    private double count(String purpose) {
        return registry.get(SiegRequestMeter.METRIC).tag("purpose", purpose).counter().count();
    }

    @Test
    void eachRequestIsCountedUnderItsPurpose() {
        SiegRequestMeter meter = meter();

        ProviderCallContext.run(ProviderCallPurpose.PAGE, () -> {
            meter.record(true);   // the side's contar-xmls
            meter.record(false);  // the side's page
        });
        ProviderCallContext.run(ProviderCallPurpose.EVENTS, () -> meter.record(false));
        ProviderCallContext.run(ProviderCallPurpose.NFSE, () -> {
            meter.record(true);
            meter.record(false);
        });
        ProviderCallContext.run(ProviderCallPurpose.RECOVERY, () -> meter.record(false));
        ProviderCallContext.run(ProviderCallPurpose.PAGE,
                () -> ProviderCallContext.run(ProviderCallPurpose.RECONCILE, () -> meter.record(false)));
        ProviderCallContext.priority(true, () -> ProviderCallContext.run(ProviderCallPurpose.PAGE, () -> meter.record(true)));
        meter.record(false); // no scope: a user's own request

        assertThat(count("count")).isEqualTo(1);
        assertThat(count("page")).isEqualTo(1);
        assertThat(count("events")).isEqualTo(1);
        assertThat(count("nfse")).isEqualTo(2);
        assertThat(count("recovery")).isEqualTo(1);
        assertThat(count("reconcile")).isEqualTo(1);
        assertThat(count("priority")).isEqualTo(1);
        assertThat(count("manual")).isEqualTo(1);
    }

    @Test
    void theSummaryHasOnlyCountsAndStartsOverAfterwards() {
        SiegRequestMeter meter = meter();
        ProviderCallContext.run(ProviderCallPurpose.PAGE, () -> {
            meter.record(true);
            meter.record(true);
            meter.record(false);
        });

        assertThat(meter.summary()).isEqualTo(
                "total=3 count=2 page=1 reconcile=0 previous=0 events=0 nfse=0 recovery=0 priority=0 manual=0");
        assertThat(meter.summary()).startsWith("total=0 ");
        assertThat(count("count")).as("the Micrometer counter is cumulative").isEqualTo(2);
    }

    @Test
    void worksWithoutARegistry() {
        SiegRequestMeter meter = new SiegRequestMeter(new SiegProperties(), new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class));

        meter.record(false);

        assertThat(meter.summary()).startsWith("total=1 ");
    }
}
