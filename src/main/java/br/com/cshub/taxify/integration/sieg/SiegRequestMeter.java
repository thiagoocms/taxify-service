package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.integration.fiscalxml.ProviderCallContext;
import br.com.cshub.taxify.integration.fiscalxml.ProviderCallPurpose;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts every request to SIEG's API (the account's 2 requests/minute are shared by every company): the Micrometer
 * counter {@code sieg.requests} with the tag {@code purpose} ({@link ProviderCallPurpose#tag()}), and a once-a-day log
 * line of this instance's totals - counts only, never a company, a key or a body.
 */
@Component
public class SiegRequestMeter {

    static final String METRIC = "sieg.requests";
    private static final Logger log = LoggerFactory.getLogger(SiegRequestMeter.class);

    private final SiegProperties properties;
    /** Fixed keys, filled once: reads and increments need no lock. */
    private final Map<ProviderCallPurpose, LongAdder> sinceLastSummary;
    private final Map<ProviderCallPurpose, Counter> counters;
    private final AtomicBoolean summarizing = new AtomicBoolean(false);

    public SiegRequestMeter(SiegProperties properties, ObjectProvider<MeterRegistry> registry) {
        this.properties = properties;
        Map<ProviderCallPurpose, LongAdder> adders = new EnumMap<>(ProviderCallPurpose.class);
        Map<ProviderCallPurpose, Counter> meters = new EnumMap<>(ProviderCallPurpose.class);
        MeterRegistry meterRegistry = registry.getIfAvailable();
        for (ProviderCallPurpose purpose : ProviderCallPurpose.values()) {
            adders.put(purpose, new LongAdder());
            if (meterRegistry != null) {
                meters.put(purpose, Counter.builder(METRIC).tag("purpose", purpose.tag())
                        .description("Requests sent to SIEG's API, by what they were for").register(meterRegistry));
            }
        }
        this.sinceLastSummary = Collections.unmodifiableMap(adders);
        this.counters = Collections.unmodifiableMap(meters);
    }

    /** One request about to go out; {@code countRequest}: contar-xmls (otherwise a download page). */
    public void record(boolean countRequest) {
        ProviderCallPurpose purpose = resolve(ProviderCallContext.current(), countRequest);
        sinceLastSummary.get(purpose).increment();
        Counter counter = counters.get(purpose);
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * The regular capture is scoped as {@link ProviderCallPurpose#PAGE}: its contar-xmls is a {@link ProviderCallPurpose#COUNT}.
     * Every other scope keeps its own purpose for both kinds of call; no scope is a user's own request.
     */
    static ProviderCallPurpose resolve(ProviderCallPurpose scope, boolean countRequest) {
        if (scope == null) {
            return ProviderCallPurpose.MANUAL;
        }
        if (scope == ProviderCallPurpose.PAGE && countRequest) {
            return ProviderCallPurpose.COUNT;
        }
        return scope;
    }

    /** This instance's requests since the last summary, then zeroed (each instance logs its own). */
    @Scheduled(cron = "${sieg.request-log.cron:0 59 23 * * *}", zone = "America/Recife")
    public void logSummary() {
        if (!properties.getRequestLog().isEnabled() || !summarizing.compareAndSet(false, true)) {
            return;
        }
        try {
            log.info("SIEG requests since the last summary (this instance): {}", summary());
        } finally {
            summarizing.set(false);
        }
    }

    /** "total=N count=N page=N ..." - zeroes the counts it reports. */
    String summary() {
        StringJoiner parts = new StringJoiner(" ");
        long total = 0;
        for (Map.Entry<ProviderCallPurpose, LongAdder> entry : sinceLastSummary.entrySet()) {
            long value = entry.getValue().sumThenReset();
            total += value;
            parts.add(entry.getKey().tag() + "=" + value);
        }
        return "total=" + total + " " + parts;
    }
}
