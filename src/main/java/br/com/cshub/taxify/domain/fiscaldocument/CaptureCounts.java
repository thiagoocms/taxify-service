package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The capture counts of one company for one channel and document type (FD-RN-32): the rows of
 * {@code capture_period_counts}, one per side and month, read with the same meaning the fixed counters of
 * {@code fiscal_xml_sync_control} had (spec 3.11) - pure, no I/O; the caller loads and saves the rows.
 *
 * <ul>
 *   <li><b>window</b>: the one of the latest month written by a sync (a row with a window) - shared by both sides, as
 *       the old {@code data_emissao_inicio}/{@code data_emissao_fim};</li>
 *   <li><b>current</b> of a side: its latest row written by a sync - the old {@code total_sieg}, {@code total_importado},
 *       {@code ultima_verificacao_em} (received) and {@code issued_*} (issued), which stay on the month last checked
 *       until the side is checked again;</li>
 *   <li><b>previous</b> of a side: its latest closed month with a count - the old {@code *_prev_*} (CP-RN-22);</li>
 *   <li><b>known gap</b>: per month, on the month's row - the old two slots of V85, without their limit of two.</li>
 * </ul>
 */
public final class CaptureCounts {

    private final Company company;
    private final ProviderKey channel;
    private final FiscalDocumentType documentType;
    private final List<CapturePeriodCount> rows;
    private final Set<CapturePeriodCount> changed = new LinkedHashSet<>();

    private CaptureCounts(Company company, ProviderKey channel, FiscalDocumentType documentType,
                          Collection<CapturePeriodCount> rows) {
        this.company = company;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.documentType = Objects.requireNonNull(documentType, "documentType");
        this.rows = new ArrayList<>(rows);
    }

    /** The counts of {@code company} from its rows of {@code channel} and {@code documentType} (other rows are ignored). */
    public static CaptureCounts of(Company company, ProviderKey channel, FiscalDocumentType documentType,
                                   Collection<CapturePeriodCount> rows) {
        List<CapturePeriodCount> own = rows.stream()
                .filter(row -> channel.equals(row.getChannel()) && documentType == row.getDocumentType())
                .toList();
        return new CaptureCounts(company, channel, documentType, own);
    }

    /** Read-only counts (no company to create rows for) - the capture panel. */
    public static CaptureCounts readOnly(ProviderKey channel, FiscalDocumentType documentType,
                                         Collection<CapturePeriodCount> rows) {
        return of(null, channel, documentType, rows);
    }

    /** The NF-e counts of the SIEG (the only channel with counts today). */
    public static CaptureCounts siegNfe(Company company, Collection<CapturePeriodCount> rows) {
        return of(company, ProviderKey.SIEG, FiscalDocumentType.NFE, rows);
    }

    // ---------------------------------------------------------------- reading

    public Optional<CapturePeriodCount> row(CaptureSide side, YearMonth month) {
        return rows.stream().filter(row -> row.getSide() == side && row.getPeriodMonth().equals(month)).findFirst();
    }

    /** The latest row written by a sync, of any side - its window is the shared one. */
    private Optional<CapturePeriodCount> latestWindowRow() {
        return rows.stream().filter(CaptureCounts::hasWindow).max(Comparator.comparing(CapturePeriodCount::getPeriodMonth));
    }

    public LocalDate windowStart() {
        return latestWindowRow().map(CapturePeriodCount::getWindowStart).orElse(null);
    }

    public LocalDate windowEnd() {
        return latestWindowRow().map(CapturePeriodCount::getWindowEnd).orElse(null);
    }

    /** The latest month of the side written by a sync (null = the side was never synced). */
    public Optional<CapturePeriodCount> current(CaptureSide side) {
        return rows.stream().filter(row -> row.getSide() == side && hasWindow(row))
                .max(Comparator.comparing(CapturePeriodCount::getPeriodMonth));
    }

    /** How the side's last closed month closed: the latest closed row with a count (rows with only a gap are not). */
    public Optional<CapturePeriodCount> previous(CaptureSide side) {
        return rows.stream().filter(row -> row.getSide() == side && row.isClosed() && row.getProviderTotal() != null)
                .max(Comparator.comparing(CapturePeriodCount::getPeriodMonth));
    }

    /** The last time the side was checked, in any month (null = never). */
    public LocalDateTime lastCheckedAt(CaptureSide side) {
        return rows.stream().filter(row -> row.getSide() == side).map(CapturePeriodCount::getImportedCountedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    /** The difference a complete pass left on the side for {@code month}; 0 = none known. */
    public int knownGap(CaptureSide side, YearMonth month) {
        if (month == null) {
            return 0;
        }
        return row(side, month).map(CapturePeriodCount::getKnownGap).orElse(0);
    }

    // ---------------------------------------------------------------- writing

    /** The row of the side and month, created when missing. */
    public CapturePeriodCount rowFor(CaptureSide side, YearMonth month) {
        CapturePeriodCount row = row(side, month).orElseGet(() -> {
            Objects.requireNonNull(company, "read-only counts");
            CapturePeriodCount created = new CapturePeriodCount(company, channel, documentType, side, month);
            rows.add(created);
            return created;
        });
        changed.add(row);
        return row;
    }

    /**
     * Keeps {@code gap} as the month's difference. A month without a row gets one - closed when it is not the month of
     * the current window (spec 3.11: a gap of a month without a row creates the row closed).
     */
    public void recordKnownGap(CaptureSide side, YearMonth month, int gap, LocalDateTime at) {
        boolean existed = row(side, month).isPresent();
        CapturePeriodCount row = rowFor(side, month);
        row.recordKnownGap(gap);
        LocalDate windowStart = windowStart();
        if (!existed && (windowStart == null || !YearMonth.from(windowStart).equals(month))) {
            row.close(at);
        }
    }

    /**
     * The previous month checked again: refreshed only when it is the side's closed month (the old
     * {@code refreshPreviousMonth}); otherwise nothing changes.
     */
    public void refreshPreviousMonth(CaptureSide side, YearMonth month, int providerTotal, int imported) {
        previous(side).filter(row -> row.getPeriodMonth().equals(month)).ifPresent(row -> {
            row.refreshClosed(providerTotal, imported);
            changed.add(row);
        });
    }

    /**
     * CP-RN-22: the window moves to a later month - the old window's month is closed on each side that was checked in it
     * (with one day of margin: the window's day comes from the JVM's zone, the check time from Recife's). A side never
     * checked in the old window keeps its earlier closed month. A window going back closes nothing.
     */
    public void closePreviousMonth(LocalDate newWindowStart, LocalDateTime at) {
        LocalDate oldStart = windowStart();
        if (oldStart == null || newWindowStart == null || !YearMonth.from(newWindowStart).isAfter(YearMonth.from(oldStart))) {
            return;
        }
        YearMonth month = YearMonth.from(oldStart);
        LocalDate checkedFrom = oldStart.minusDays(1);
        for (CaptureSide side : List.of(CaptureSide.RECEIVED, CaptureSide.ISSUED)) {
            row(side, month)
                    .filter(row -> !row.isClosed() && row.getImportedCountedAt() != null
                            && !row.getImportedCountedAt().toLocalDate().isBefore(checkedFrom))
                    .ifPresent(row -> {
                        row.close(at);
                        changed.add(row);
                    });
        }
    }

    /** The rows created or changed since loaded - what the caller saves. */
    public List<CapturePeriodCount> changedRows() {
        return List.copyOf(changed);
    }

    public Company getCompany() {
        return company;
    }

    private static boolean hasWindow(CapturePeriodCount row) {
        return row.getWindowStart() != null;
    }
}
