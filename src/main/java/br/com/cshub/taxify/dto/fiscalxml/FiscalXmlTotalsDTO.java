package br.com.cshub.taxify.dto.fiscalxml;

import java.math.BigDecimal;

/**
 * The cards above the notes list, entries and exits apart: the whole NF-e and NFC-e matching every filter of the list
 * but the tab (spec 2026-11-27, CF-RN-10 to 12).
 *
 * @param filtered  CF-RN-17: some filter beyond the company, the period and the tab is on ("Totais do filtro")
 * @param cfopItems CF-RN-13: with a CFOP filter, only the items in those CFOPs (vProd, without freight, insurance and
 *                  discount); null without one
 */
public record FiscalXmlTotalsDTO(DirectionTotals inbound, DirectionTotals outbound, boolean filtered, CfopItems cfopItems) {

    public record DirectionTotals(
            long count,
            BigDecimal total,
            BigDecimal icms,
            BigDecimal icmsSt,
            BigDecimal ipi,
            BigDecimal pis,
            BigDecimal cofins,
            /** Canceled notes of the period - listed, but left out of every sum above. */
            long canceledCount
    ) {
        public static DirectionTotals empty() {
            return new DirectionTotals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        /** Exact sums (CF-RN-16): {@code BigDecimal.add}, no rounding - the columns already have scale 2. */
        public DirectionTotals plus(DirectionTotals other) {
            return new DirectionTotals(count + other.count, total.add(other.total), icms.add(other.icms),
                    icmsSt.add(other.icmsSt), ipi.add(other.ipi), pis.add(other.pis), cofins.add(other.cofins),
                    canceledCount + other.canceledCount);
        }
    }

    /** CF-RN-13, per direction. */
    public record CfopItems(ItemTotals inbound, ItemTotals outbound) {
    }

    /** The items in the filtered CFOPs of the non-canceled notes: how many, their vProd and their taxes. */
    public record ItemTotals(
            long itemCount,
            BigDecimal itemsAmount,
            BigDecimal icms,
            BigDecimal icmsSt,
            BigDecimal ipi,
            BigDecimal pis,
            BigDecimal cofins
    ) {
        public static ItemTotals empty() {
            return new ItemTotals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO);
        }

        public ItemTotals plus(ItemTotals other) {
            return new ItemTotals(itemCount + other.itemCount, itemsAmount.add(other.itemsAmount), icms.add(other.icms),
                    icmsSt.add(other.icmsSt), ipi.add(other.ipi), pis.add(other.pis), cofins.add(other.cofins));
        }
    }
}
