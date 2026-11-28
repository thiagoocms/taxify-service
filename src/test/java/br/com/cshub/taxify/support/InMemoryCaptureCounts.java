package br.com.cshub.taxify.support;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.CaptureCounts;
import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.domain.fiscaldocument.ProviderKey;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import br.com.cshub.taxify.repository.CapturePeriodCountRepository;
import br.com.cshub.taxify.service.capture.CaptureCountsService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The capture counts ({@code capture_period_counts}, FD-RN-32) of the unit tests, kept in memory: a
 * {@link CaptureCountsService} over a repository whose reads and writes go to a list. What the old tests set and read
 * on the counter columns of {@code fiscal_xml_sync_control} is set and read here, per side and month.
 */
public final class InMemoryCaptureCounts {

    private final List<CapturePeriodCount> rows = new ArrayList<>();
    private final CapturePeriodCountRepository repository = mock(CapturePeriodCountRepository.class);
    private final CaptureCountsService service = new CaptureCountsService(repository);

    @SuppressWarnings("unchecked")
    public InMemoryCaptureCounts() {
        lenient().when(repository.findOfCompany(any(), any(), any())).thenAnswer(call -> rows.stream()
                .filter(row -> Objects.equals(row.getCompany().getId(), call.getArgument(0)))
                .filter(row -> row.getChannel().equals(call.getArgument(1)) && row.getDocumentType() == call.getArgument(2))
                .toList());
        lenient().when(repository.findOfCompanies(anyCollection(), any(), any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return rows.stream().filter(row -> ids.contains(row.getCompany().getId())).toList();
        });
        lenient().when(repository.saveAll(any())).thenAnswer(call -> {
            for (CapturePeriodCount row : (Iterable<CapturePeriodCount>) call.getArgument(0)) {
                if (rows.stream().noneMatch(existing -> existing == row)) {
                    rows.add(row);
                }
            }
            return call.getArgument(0);
        });
        lenient().when(repository.lastCheckedBySide(any(), any())).thenAnswer(call -> {
            List<Object[]> result = new ArrayList<>();
            rows.stream().map(row -> row.getCompany().getId()).distinct().forEach(companyId -> {
                for (CaptureSide side : CaptureSide.values()) {
                    rows.stream().filter(row -> row.getCompany().getId().equals(companyId) && row.getSide() == side)
                            .map(CapturePeriodCount::getImportedCountedAt).filter(Objects::nonNull)
                            .max(LocalDateTime::compareTo)
                            .ifPresent(at -> result.add(new Object[]{companyId, side, at}));
                }
            });
            return result;
        });
    }

    public CaptureCountsService service() {
        return service;
    }

    public CapturePeriodCountRepository repository() {
        return repository;
    }

    public List<CapturePeriodCount> rows() {
        return rows;
    }

    /** The SIEG NF-e counts of the company as the code reads them. */
    public CaptureCounts of(Company company) {
        return CaptureCounts.siegNfe(company, rows);
    }

    public Optional<CapturePeriodCount> row(UUID companyId, CaptureSide side, YearMonth month) {
        return rows.stream().filter(row -> row.getCompany().getId().equals(companyId) && row.getSide() == side
                && row.getPeriodMonth().equals(month)).findFirst();
    }

    public CapturePeriodCount require(UUID companyId, CaptureSide side, YearMonth month) {
        return row(companyId, side, month).orElseThrow(() -> new AssertionError("no counts of " + side + " " + month));
    }

    /**
     * A month already checked: the provider's total ({@code null} = never counted), what it delivered and when, with the
     * window of the month (from day 1).
     */
    public CapturePeriodCount seed(Company company, CaptureSide side, YearMonth month, Integer providerTotal, int imported,
                                   LocalDateTime checkedAt) {
        CapturePeriodCount row = new CapturePeriodCount(company, ProviderKey.SIEG, FiscalDocumentType.NFE, side, month);
        LocalDate start = month.atDay(1);
        row.askedWindow(start, month.atEndOfMonth());
        row.recordProviderCount(providerTotal, checkedAt);
        row.recordImported(imported, checkedAt);
        rows.add(row);
        return row;
    }

    /** A known gap of a month (the old V85 slots). */
    public void gap(Company company, CaptureSide side, YearMonth month, int gap) {
        CapturePeriodCount row = row(company.getId(), side, month).orElseGet(() -> {
            CapturePeriodCount created = new CapturePeriodCount(company, ProviderKey.SIEG, FiscalDocumentType.NFE, side, month);
            rows.add(created);
            return created;
        });
        row.recordKnownGap(gap);
    }
}
