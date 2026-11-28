package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsTotals;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotalsDTO.CfopItems;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotalsDTO.DirectionTotals;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotalsDTO.ItemTotals;
import br.com.cshub.taxify.enumerated.FiscalDirection;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * The sums of the cards of the notes screen, pure (spec 2026-11-27, CF-RN-12 to 16): from the rows of the queries, or -
 * on the "só divergentes" path - from the notes themselves, with the same rules as the query: a canceled note counts only
 * in {@code canceledCount}; the sums are exact ({@code BigDecimal.add}, no rounding); a note without direction counts as
 * an entry, as on the cards since the beginning.
 */
final class NoteCardTotals {

    private NoteCardTotals() {
    }

    /** Inbound and outbound of the cards. */
    record Cards(DirectionTotals inbound, DirectionTotals outbound) {

        static final Cards EMPTY = new Cards(DirectionTotals.empty(), DirectionTotals.empty());

        Cards plus(FiscalDirection direction, DirectionTotals totals) {
            return direction == FiscalDirection.OUTBOUND
                    ? new Cards(inbound, outbound.plus(totals))
                    : new Cards(inbound.plus(totals), outbound);
        }
    }

    /** Rows of {@code GoodsInvoiceRepository.TOTALS_BY_DIRECTION}. */
    static Cards fromRows(List<Object[]> rows) {
        Cards cards = Cards.EMPTY;
        for (Object[] row : rows) {
            cards = cards.plus((FiscalDirection) row[0], new DirectionTotals(count(row[1]), decimal(row[2]), decimal(row[3]),
                    decimal(row[4]), decimal(row[5]), decimal(row[6]), decimal(row[7]), count(row[8])));
        }
        return cards;
    }

    /** The same sums over the notes kept in memory (CF-RN-15). */
    static Cards sum(Collection<GoodsInvoice> notes) {
        Cards cards = Cards.EMPTY;
        for (GoodsInvoice note : notes) {
            cards = cards.plus(note.getDirection(), of(note));
        }
        return cards;
    }

    private static DirectionTotals of(GoodsInvoice note) {
        if (note.isCanceled()) {
            return new DirectionTotals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, 1);
        }
        GoodsTotals totals = note.getGoodsTotals() == null ? GoodsTotals.EMPTY : note.getGoodsTotals();
        return new DirectionTotals(1, orZero(note.getTotalAmount()), orZero(totals.icmsAmount()), orZero(totals.icmsStAmount()),
                orZero(totals.ipiAmount()), orZero(totals.pisAmount()), orZero(totals.cofinsAmount()), 0);
    }

    /** Rows of {@code FiscalDocumentItemRepository.CFOP_ITEM_TOTALS}, added to {@code items}. */
    static CfopItems addItemRows(CfopItems items, List<Object[]> rows) {
        CfopItems result = items;
        for (Object[] row : rows) {
            ItemTotals totals = new ItemTotals(count(row[1]), decimal(row[2]), decimal(row[3]), decimal(row[4]), decimal(row[5]),
                    decimal(row[6]), decimal(row[7]));
            result = row[0] == FiscalDirection.OUTBOUND
                    ? new CfopItems(result.inbound(), result.outbound().plus(totals))
                    : new CfopItems(result.inbound().plus(totals), result.outbound());
        }
        return result;
    }

    static CfopItems emptyItems() {
        return new CfopItems(ItemTotals.empty(), ItemTotals.empty());
    }

    private static long count(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** SUM over a CASE comes back as BigDecimal or Long/Integer depending on the dialect. */
    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
