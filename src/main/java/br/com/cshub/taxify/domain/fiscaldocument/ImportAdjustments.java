package br.com.cshub.taxify.domain.fiscaldocument;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What the model adjusted while one imported document was built (decision of 2026-11-28: no note is lost, every
 * adjustment is logged): a value with more decimal places than its column, rounded (I-17, {@link FiscalDecimals}), a
 * party document shorter than its tag's size, completed with zeros (I-06, {@link PartyDocuments}), or one the model
 * refuses (longer than the tag's size, not only digits, or both CNPJ and CPF). The import builds the document inside
 * {@link #collect} and logs each one - the field and a size, never the value. Outside a collection (e.g. Hibernate
 * reading a row) nothing is recorded.
 */
public final class ImportAdjustments {

    public enum Kind {
        /** I-17: rounded HALF_UP to the column's scale; {@code size} = decimal places it came with. */
        DECIMAL_ROUNDED,
        /** I-06: zeros on the left up to the tag's size; {@code size} = digits it came with. */
        PARTY_PADDED,
        /** I-06: kept as it came, so the document breaks I-06 and is not stored; {@code size} = characters. */
        PARTY_REFUSED
    }

    /** One adjustment: {@code field} as {@code Type.field} (or the party and its tag). */
    public record Adjustment(Kind kind, String field, int size) {
    }

    /** What {@link #collect} built and the adjustments made meanwhile. */
    public record Collected<T>(T value, List<Adjustment> adjustments) {

        public long count(Kind kind) {
            return adjustments.stream().filter(adjustment -> adjustment.kind() == kind).count();
        }
    }

    private static final ThreadLocal<List<Adjustment>> CURRENT = new ThreadLocal<>();

    private ImportAdjustments() {
    }

    /** Runs {@code work} recording the adjustments it makes (a nested collection also records into the outer one). */
    public static <T> Collected<T> collect(Supplier<T> work) {
        List<Adjustment> outer = CURRENT.get();
        List<Adjustment> mine = new ArrayList<>();
        CURRENT.set(mine);
        try {
            T value = work.get();
            return new Collected<>(value, List.copyOf(mine));
        } finally {
            if (outer == null) {
                CURRENT.remove();
            } else {
                outer.addAll(mine);
                CURRENT.set(outer);
            }
        }
    }

    static void record(Kind kind, String field, int size) {
        List<Adjustment> current = CURRENT.get();
        if (current != null) {
            current.add(new Adjustment(kind, field, size));
        }
    }
}
