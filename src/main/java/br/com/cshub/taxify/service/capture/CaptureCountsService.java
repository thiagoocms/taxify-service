package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.CaptureCounts;
import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.domain.fiscaldocument.ProviderKey;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.repository.CapturePeriodCountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Loads and saves the capture counts of a company (FD-RN-32): {@link CaptureCounts} over the rows of
 * {@code capture_period_counts} of the SIEG NF-e channel. A fixed number of queries per call: one to load, one save
 * per changed row.
 */
@Service
public class CaptureCountsService {

    static final String WRITER = "system";

    private final CapturePeriodCountRepository repository;
    private final Clock clock;

    public CaptureCountsService(CapturePeriodCountRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** The SIEG NF-e counts of the company, every month. */
    @Transactional(readOnly = true)
    public CaptureCounts siegNfe(Company company) {
        return CaptureCounts.siegNfe(company, repository.findOfCompany(company.getId(), ProviderKey.SIEG, FiscalDocumentType.NFE));
    }

    /**
     * Writes the rows the counts created or changed: a row already stored is updated as an entity; a row created now
     * goes as an atomic upsert ({@link CapturePeriodCountRepository#upsert}) - two writers creating the same month
     * (the scheduler and a manual sync, or two instances) never fail on the unique key (I-3).
     */
    @Transactional
    public void save(CaptureCounts counts) {
        List<CapturePeriodCount> stored = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(clock);
        for (CapturePeriodCount row : counts.changedRows()) {
            if (row.getId() == null) {
                insert(row, now);
            } else {
                stored.add(row);
            }
        }
        if (!stored.isEmpty()) {
            repository.saveAll(stored);
        }
    }

    /** One row created by the counts, upserted (overridden by the in-memory counts of the unit tests). */
    protected void insert(CapturePeriodCount row, LocalDateTime now) {
        repository.upsert(UUID.randomUUID().toString(), row.getCompany().getId().toString(), row.getChannel().value(),
                row.getDocumentType().name(), row.getSide().name(), row.getPeriodMonth().toString(), row.getWindowStart(),
                row.getWindowEnd(), row.getProviderTotal(), row.getProviderCountedAt(), row.getImportedTotal(),
                row.getStoredAnySourceTotal(), row.getKnownGap(), row.getImportedCountedAt(), row.getClosedAt(), WRITER, now);
    }

    /** The SIEG NF-e counts of several companies at once (one query). Companies without any row get empty counts. */
    @Transactional(readOnly = true)
    public Map<UUID, CaptureCounts> siegNfe(Collection<Company> companies) {
        Map<UUID, Company> byId = new HashMap<>();
        companies.forEach(company -> byId.put(company.getId(), company));
        Map<UUID, List<CapturePeriodCount>> rows = new HashMap<>();
        if (!byId.isEmpty()) {
            repository.findOfCompanies(byId.keySet(), ProviderKey.SIEG, FiscalDocumentType.NFE)
                    .forEach(row -> rows.computeIfAbsent(row.getCompany().getId(), id -> new ArrayList<>()).add(row));
        }
        Map<UUID, CaptureCounts> result = new HashMap<>();
        byId.forEach((id, company) -> result.put(id, CaptureCounts.siegNfe(company, rows.getOrDefault(id, List.of()))));
        return result;
    }

    /**
     * When each side of each company was last checked on the SIEG, any month (one query) - the queue order of the
     * scheduler. Companies never checked are absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Map<CaptureSide, LocalDateTime>> siegLastChecks() {
        Map<UUID, Map<CaptureSide, LocalDateTime>> result = new HashMap<>();
        for (Object[] row : repository.lastCheckedBySide(ProviderKey.SIEG, FiscalDocumentType.NFE)) {
            result.computeIfAbsent((UUID) row[0], id -> new EnumMap<>(CaptureSide.class))
                    .put((CaptureSide) row[1], (LocalDateTime) row[2]);
        }
        return result;
    }
}
