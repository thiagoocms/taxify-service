package br.com.cshub.taxify.domain.fiscalrule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The commercial unit price ({@code vUnCom}) a product was bought at, from one supplier, on the notes the company received
 * (spec PU, 2026-11-28) - informative only, never part of a rule (PU-RN-08). Pure: it is fed the aggregated groups of
 * items (one per unit as stored) and joins them by the normalized unit (PU-RN-04), so that {@code " un"} and {@code "UN"}
 * are one unit; there is no conversion between units: the price is that of the predominant unit only (most items, then
 * the greatest summed quantity, then the alphabetical order), and the others are listed apart. The average is weighted by
 * the quantity (PU-RN-05), with 4 places HALF_UP (PU-RN-06); min, max and the quantity keep the stored 4 places.
 */
public final class PurchaseUnitPrices {

    /** The unit of the items without one (null or blank). */
    public static final String NO_UNIT = "—";
    /** The scale of {@code commercial_unit_price}/{@code commercial_quantity} (D-07). */
    public static final int SCALE = 4;

    private static final Comparator<UnitGroup> PREDOMINANT = Comparator.comparingLong((UnitGroup group) -> group.items)
            .thenComparing(group -> group.quantity)
            .reversed()
            .thenComparing(group -> group.unit);

    private final Map<String, UnitGroup> byUnit = new HashMap<>();

    /**
     * Adds one aggregated group of items of the same stored unit - only items with a price {@code >= 0} and a quantity
     * {@code > 0} (PU-RN-03), which the caller's query already selects.
     *
     * @param items        how many items
     * @param quantity     Σ qCom
     * @param amount       Σ (qCom × vUnCom), not rounded
     * @param minUnitPrice the smallest vUnCom
     * @param maxUnitPrice the greatest vUnCom
     */
    public void add(String unit, long items, BigDecimal quantity, BigDecimal amount, BigDecimal minUnitPrice,
                    BigDecimal maxUnitPrice) {
        if (items <= 0 || quantity == null || quantity.signum() <= 0 || amount == null || minUnitPrice == null
                || maxUnitPrice == null) {
            return;
        }
        byUnit.computeIfAbsent(normalize(unit), UnitGroup::new).add(items, quantity, amount, minUnitPrice, maxUnitPrice);
    }

    /** {@code trim} and upper case; null or blank is {@link #NO_UNIT} (PU-RN-04). */
    public static String normalize(String unit) {
        if (unit == null || unit.isBlank()) {
            return NO_UNIT;
        }
        return unit.strip().toUpperCase(Locale.ROOT);
    }

    /** Empty when no item counts (PU-RN-03). */
    public Optional<Summary> summary() {
        if (byUnit.isEmpty()) {
            return Optional.empty();
        }
        List<UnitGroup> ordered = byUnit.values().stream().sorted(PREDOMINANT).toList();
        UnitGroup main = ordered.getFirst();
        List<OtherUnit> others = ordered.stream().skip(1)
                .sorted(Comparator.comparingLong((UnitGroup group) -> group.items).reversed().thenComparing(group -> group.unit))
                .map(group -> new OtherUnit(group.unit, group.items))
                .toList();
        return Optional.of(new Summary(
                main.amount.divide(main.quantity, SCALE, RoundingMode.HALF_UP),
                main.min.setScale(SCALE, RoundingMode.HALF_UP),
                main.max.setScale(SCALE, RoundingMode.HALF_UP),
                main.unit, main.items,
                main.quantity.setScale(SCALE, RoundingMode.HALF_UP),
                others));
    }

    /**
     * The price of the predominant unit (PU-RN-05/06).
     *
     * @param average         Σ(qCom × vUnCom) ÷ Σ qCom, 4 places HALF_UP
     * @param itemsConsidered items of the predominant unit
     * @param totalQuantity   Σ qCom of those items
     * @param otherUnits      the other units, most items first, then by unit; empty when there is only one
     */
    public record Summary(BigDecimal average, BigDecimal min, BigDecimal max, String unit, long itemsConsidered,
                          BigDecimal totalQuantity, List<OtherUnit> otherUnits) {
    }

    /** A unit the product also came in, left out of the price. */
    public record OtherUnit(String unit, long itemsConsidered) {
    }

    private static final class UnitGroup {
        private final String unit;
        private long items;
        private BigDecimal quantity = BigDecimal.ZERO;
        private BigDecimal amount = BigDecimal.ZERO;
        private BigDecimal min;
        private BigDecimal max;

        UnitGroup(String unit) {
            this.unit = unit;
        }

        void add(long items, BigDecimal quantity, BigDecimal amount, BigDecimal minUnitPrice, BigDecimal maxUnitPrice) {
            this.items += items;
            this.quantity = this.quantity.add(quantity);
            this.amount = this.amount.add(amount);
            this.min = min == null || minUnitPrice.compareTo(min) < 0 ? minUnitPrice : min;
            this.max = max == null || maxUnitPrice.compareTo(max) > 0 ? maxUnitPrice : max;
        }
    }
}
