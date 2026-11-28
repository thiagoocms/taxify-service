package br.com.cshub.taxify.domain.fiscaldocument;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.FiscalDocumentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** FD-RN-32 and spec 3.11: capture_period_counts read with the meaning of the old counters of fiscal_xml_sync_control. */
class CaptureCountsTest {

    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final LocalDateTime NOV_20 = LocalDateTime.of(2026, 11, 20, 9, 0);

    private final Company company = new Company(UUID.randomUUID());
    private final List<CapturePeriodCount> rows = new ArrayList<>();

    private CapturePeriodCount synced(CaptureSide side, YearMonth month, int providerTotal, int imported, LocalDateTime at) {
        CapturePeriodCount row = new CapturePeriodCount(company, ProviderKey.SIEG, FiscalDocumentType.NFE, side, month);
        row.askedWindow(month.atDay(1), month.atEndOfMonth());
        row.recordProviderCount(providerTotal, at);
        row.recordImported(imported, at);
        rows.add(row);
        return row;
    }

    private CaptureCounts counts() {
        return CaptureCounts.siegNfe(company, rows);
    }

    @Test
    void theCurrentOfASideIsItsLatestSyncedMonthAndTheWindowIsTheLatestOfAnySide() {
        synced(CaptureSide.RECEIVED, OCTOBER, 10, 10, NOV_20.minusMonths(1));
        synced(CaptureSide.RECEIVED, NOVEMBER, 200, 180, NOV_20);
        synced(CaptureSide.ISSUED, OCTOBER, 3, 3, NOV_20.minusMonths(1));

        CaptureCounts counts = counts();
        assertThat(counts.current(CaptureSide.RECEIVED)).get().extracting(CapturePeriodCount::getPeriodMonth).isEqualTo(NOVEMBER);
        // the issued side stays on the month last checked until it is checked again (old issued_* columns)
        assertThat(counts.current(CaptureSide.ISSUED)).get().extracting(CapturePeriodCount::getPeriodMonth).isEqualTo(OCTOBER);
        assertThat(counts.windowStart()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(counts.lastCheckedAt(CaptureSide.RECEIVED)).isEqualTo(NOV_20);
    }

    @Test
    void otherChannelsAndTypesAreIgnored() {
        CapturePeriodCount cte = new CapturePeriodCount(company, ProviderKey.SIEG, FiscalDocumentType.CTE, CaptureSide.RECEIVED, NOVEMBER);
        cte.askedWindow(NOVEMBER.atDay(1), NOVEMBER.atEndOfMonth());
        rows.add(cte);

        assertThat(counts().current(CaptureSide.RECEIVED)).isEmpty();
        assertThat(counts().windowStart()).isNull();
    }

    @Test
    void thePreviousIsTheLatestClosedMonthWithACount() {
        CapturePeriodCount october = synced(CaptureSide.RECEIVED, OCTOBER, 50, 48, NOV_20.minusMonths(1));
        october.close(NOV_20.minusDays(19));
        synced(CaptureSide.RECEIVED, NOVEMBER, 20, 20, NOV_20);
        CaptureCounts counts = counts();
        // a closed month with only a gap is not a "previous month"
        counts.recordKnownGap(CaptureSide.RECEIVED, YearMonth.of(2026, 9), 2, NOV_20);

        assertThat(counts.previous(CaptureSide.RECEIVED)).get().extracting(CapturePeriodCount::getPeriodMonth).isEqualTo(OCTOBER);
        assertThat(counts.previous(CaptureSide.ISSUED)).isEmpty();
    }

    @Test
    void aKnownGapIsKeptPerMonthAndAMonthWithoutARowGetsItClosed() {
        synced(CaptureSide.RECEIVED, NOVEMBER, 63, 60, NOV_20);
        CaptureCounts counts = counts();

        counts.recordKnownGap(CaptureSide.RECEIVED, NOVEMBER, 3, NOV_20);
        counts.recordKnownGap(CaptureSide.RECEIVED, OCTOBER, 1, NOV_20);
        counts.recordKnownGap(CaptureSide.RECEIVED, YearMonth.of(2026, 9), 4, NOV_20);

        assertThat(counts.knownGap(CaptureSide.RECEIVED, NOVEMBER)).isEqualTo(3);
        assertThat(counts.knownGap(CaptureSide.RECEIVED, OCTOBER)).isEqualTo(1);
        // no limit of two slots any more (V85): a third month keeps its own
        assertThat(counts.knownGap(CaptureSide.RECEIVED, YearMonth.of(2026, 9))).isEqualTo(4);
        assertThat(counts.knownGap(CaptureSide.ISSUED, NOVEMBER)).isZero();
        assertThat(counts.knownGap(CaptureSide.RECEIVED, null)).isZero();
        assertThat(counts.row(CaptureSide.RECEIVED, NOVEMBER).orElseThrow().isClosed()).as("the window's month stays open").isFalse();
        assertThat(counts.row(CaptureSide.RECEIVED, OCTOBER).orElseThrow().isClosed()).isTrue();
        assertThat(counts.changedRows()).hasSize(3);
    }

    @Test
    void movingTheWindowClosesTheOldMonthOnTheSidesCheckedInIt() {
        synced(CaptureSide.RECEIVED, OCTOBER, 40, 40, LocalDateTime.of(2026, 10, 31, 23, 0));
        // the issued side was last checked in September's window: it keeps its earlier state
        synced(CaptureSide.ISSUED, OCTOBER, 2, 2, LocalDateTime.of(2026, 9, 29, 8, 0));
        CaptureCounts counts = counts();

        counts.closePreviousMonth(LocalDate.of(2026, 11, 1), NOV_20);

        assertThat(counts.row(CaptureSide.RECEIVED, OCTOBER).orElseThrow().getClosedAt()).isEqualTo(NOV_20);
        assertThat(counts.row(CaptureSide.ISSUED, OCTOBER).orElseThrow().isClosed()).isFalse();
        assertThat(counts.changedRows()).hasSize(1);
    }

    @Test
    void aWindowGoingBackOrStayingClosesNothing() {
        synced(CaptureSide.RECEIVED, NOVEMBER, 40, 40, NOV_20);
        CaptureCounts counts = counts();

        counts.closePreviousMonth(LocalDate.of(2026, 10, 1), NOV_20);
        counts.closePreviousMonth(LocalDate.of(2026, 11, 15), NOV_20);

        assertThat(counts.row(CaptureSide.RECEIVED, NOVEMBER).orElseThrow().isClosed()).isFalse();
        assertThat(counts.changedRows()).isEmpty();
    }

    @Test
    void thePreviousMonthIsRefreshedOnlyWhenItIsTheClosedOne() {
        CapturePeriodCount october = synced(CaptureSide.RECEIVED, OCTOBER, 50, 48, NOV_20.minusMonths(1));
        october.close(NOV_20.minusDays(19));
        CaptureCounts counts = counts();

        counts.refreshPreviousMonth(CaptureSide.RECEIVED, YearMonth.of(2026, 9), 99, 99);
        assertThat(october.getProviderTotal()).isEqualTo(50);

        counts.refreshPreviousMonth(CaptureSide.RECEIVED, OCTOBER, 52, 52);
        assertThat(october.getProviderTotal()).isEqualTo(52);
        assertThat(october.getImportedTotal()).isEqualTo(52);
        assertThat(counts.changedRows()).containsExactly(october);
    }

    @Test
    void readOnlyCountsCreateNoRows() {
        CaptureCounts counts = CaptureCounts.readOnly(ProviderKey.SIEG, FiscalDocumentType.NFE, rows);

        assertThat(counts.knownGap(CaptureSide.RECEIVED, NOVEMBER)).isZero();
        assertThatNullPointerException().isThrownBy(() -> counts.rowFor(CaptureSide.RECEIVED, NOVEMBER));
    }
}
