package br.com.cshub.taxify.domain.reform.stock;

import br.com.cshub.taxify.enumerated.StockSurveyCoverage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The facts of one company's 2026 purchases and inbound coverage (CE-RN-04 to CE-RN-06), computed from the
 * aggregates of the database - pure, without I/O. None of them says what enters the credit: this is a survey, not a
 * credit calculation (CE-RN-13).
 */
public record StockSurveyFigures(BigDecimal purchasesTotal, int purchaseItems, int inboundNotes,
                                 List<MonthFigure> purchasesByMonth, List<GroupFigure> purchasesByCstPis,
                                 List<GroupFigure> topNcm, int monthsWithInbound, int monthsEvaluated,
                                 StockSurveyCoverage coverage, LocalDate firstInbound, LocalDate lastInbound) {

    public static final String NO_CST = "NO_CST";
    public static final String NO_NCM = "NO_NCM";
    public static final int TOP_NCM = 10;
    static final int MONEY_SCALE = 2;

    /** Purchases of one month (CE-RN-04 base): sum of the items, number of items and of distinct notes. */
    public record MonthlyPurchases(int month, BigDecimal value, int items, int notes) {
    }

    /** Purchases of one CST of PIS or one NCM (CE-RN-05); {@code key} as stored, trimmed by the query or not. */
    public record PurchaseGroup(String key, BigDecimal value, int items) {
    }

    /** Inbound notes of one month with any CFOP (CE-RN-06): first and last issue date. */
    public record InboundMonth(int month, LocalDate first, LocalDate last) {
    }

    /** One of the 12 months: "2026-01", value with 2 decimals, notes. */
    public record MonthFigure(String month, BigDecimal value, int notes) {
    }

    /** One CST or NCM: value with 2 decimals, items. */
    public record GroupFigure(String key, BigDecimal value, int items) {
    }

    /**
     * @param today the date of Recife of the calculation - decides how many months are evaluated (CE-RN-06)
     */
    public static StockSurveyFigures of(Collection<MonthlyPurchases> months, Collection<PurchaseGroup> cstGroups,
                                        Collection<PurchaseGroup> ncmGroups, Collection<InboundMonth> inbound, LocalDate today) {
        BigDecimal total = BigDecimal.ZERO;
        int items = 0;
        int notes = 0;
        Map<Integer, MonthlyPurchases> byMonth = new HashMap<>();
        for (MonthlyPurchases month : months) {
            total = total.add(orZero(month.value()));
            items += month.items();
            // a note has one issue date: its distinct count per month adds up to the year's
            notes += month.notes();
            byMonth.merge(month.month(), month, (a, b) -> new MonthlyPurchases(a.month(), orZero(a.value()).add(orZero(b.value())),
                    a.items() + b.items(), a.notes() + b.notes()));
        }
        List<MonthFigure> twelve = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            MonthlyPurchases found = byMonth.get(month);
            twelve.add(new MonthFigure("%d-%02d".formatted(StockSurveyCalendar.YEAR, month),
                    money(found == null ? BigDecimal.ZERO : found.value()), found == null ? 0 : found.notes()));
        }
        InboundCoverage coverage = InboundCoverage.of(inbound, today);
        return new StockSurveyFigures(money(total), items, notes, twelve, grouped(cstGroups, NO_CST, Integer.MAX_VALUE),
                grouped(ncmGroups, NO_NCM, TOP_NCM), coverage.monthsWithInbound(), coverage.monthsEvaluated(),
                coverage.coverage(), coverage.first(), coverage.last());
    }

    /** Merged by the trimmed key (null or blank = {@code missing}), by value descending then key ascending, cut at {@code limit}. */
    static List<GroupFigure> grouped(Collection<PurchaseGroup> groups, String missing, int limit) {
        Map<String, GroupFigure> merged = new LinkedHashMap<>();
        for (PurchaseGroup group : groups) {
            String key = group.key() == null || group.key().isBlank() ? missing : group.key().strip();
            merged.merge(key, new GroupFigure(key, orZero(group.value()), group.items()),
                    (a, b) -> new GroupFigure(key, a.value().add(b.value()), a.items() + b.items()));
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(GroupFigure::value).reversed().thenComparing(GroupFigure::key))
                .limit(limit)
                .map(group -> new GroupFigure(group.key(), money(group.value()), group.items()))
                .toList();
    }

    /** Exact sum shown with 2 decimals, HALF_UP (CE-RN-04). */
    static BigDecimal money(BigDecimal value) {
        return orZero(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
