package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The month calculated from the notes, per company - the firm-wide assessment (every company, 13 months of items
 * summed) is the heaviest read of the app and the screen is opened all the time. Only the calculation is kept:
 * closings and the "to review" counter are read fresh on every request.
 *
 * <p>Any change to what the calculation reads (notes, items, the company, its regime history) drops that company's
 * entries ({@link TaxAssessmentCacheInvalidator}); {@link #TTL_NANOS} is only the safety net. A calculation that
 * started before a change is not stored: each company has a version, bumped on every eviction, and
 * {@link #put} only keeps a result computed under the current version.
 */
@Component
public class TaxAssessmentCache {

    static final long TTL_NANOS = 5L * 60 * 1_000_000_000;
    /** Way above companies x months anyone looks at; past it the cache just starts over. */
    static final int MAX_ENTRIES = 20_000;

    private record Key(UUID companyId, YearMonth month) {
    }

    private record Entry(TaxAssessmentDTO value, long expiresAt) {
    }

    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> versions = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public TaxAssessmentCache() {
        this(System::nanoTime);
    }

    TaxAssessmentCache(LongSupplier clock) {
        this.clock = clock;
    }

    /** Read before calculating; pass it to {@link #put}. */
    public long version(UUID companyId) {
        return versions.computeIfAbsent(companyId, id -> new AtomicLong()).get();
    }

    public Optional<TaxAssessmentDTO> get(UUID companyId, YearMonth month) {
        Entry entry = entries.get(new Key(companyId, month));
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.getAsLong() - entry.expiresAt() > 0) {
            entries.remove(new Key(companyId, month), entry);
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    /** Kept only if nothing of the company changed since {@code versionBefore} was read. */
    public void put(UUID companyId, YearMonth month, TaxAssessmentDTO value, long versionBefore) {
        if (version(companyId) != versionBefore) {
            return;
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
        entries.put(new Key(companyId, month), new Entry(value, clock.getAsLong() + TTL_NANOS));
    }

    public void evictCompany(UUID companyId) {
        versions.computeIfAbsent(companyId, id -> new AtomicLong()).incrementAndGet();
        entries.keySet().removeIf(key -> key.companyId().equals(companyId));
    }

    public void evictAll() {
        versions.values().forEach(AtomicLong::incrementAndGet);
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
