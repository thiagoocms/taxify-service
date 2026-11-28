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

    private final CapturePeriodCountRepository repository;

    public CaptureCountsService(CapturePeriodCountRepository repository) {
        this.repository = repository;
    }

    /** The SIEG NF-e counts of the company, every month. */
    @Transactional(readOnly = true)
    public CaptureCounts siegNfe(Company company) {
        return CaptureCounts.siegNfe(company, repository.findOfCompany(company.getId(), ProviderKey.SIEG, FiscalDocumentType.NFE));
    }

    /** Writes the rows the counts created or changed. */
    @Transactional
    public void save(CaptureCounts counts) {
        repository.saveAll(counts.changedRows());
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
