package br.com.cshub.taxify.domain.reform.stock;

import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.service.assessment.RevenueCfops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * CE-RN-04, {@code CFOPS_COMPRA}: every 4-digit code from 1000 to 7999 that the assessment classifies as a purchase on
 * an inbound note ({@code RevenueCfops.classify(INBOUND, c) == PURCHASE}) - the same criterion, never a second list.
 * Derived once (the class is immutable).
 */
public final class PurchaseCfops {

    private static final SortedSet<String> CODES = derive();

    private PurchaseCfops() {
    }

    public static SortedSet<String> codes() {
        return CODES;
    }

    /** CE-RN-14: the list compacted in ranges of consecutive codes, e.g. "1101-1102, 1126, 1401, 1403, ...". */
    public static String compacted() {
        List<String> parts = new ArrayList<>();
        Integer start = null;
        Integer previous = null;
        for (String code : CODES) {
            int value = Integer.parseInt(code);
            if (previous != null && value == previous + 1) {
                previous = value;
                continue;
            }
            if (start != null) {
                parts.add(range(start, previous));
            }
            start = value;
            previous = value;
        }
        if (start != null) {
            parts.add(range(start, previous));
        }
        return String.join(", ", parts);
    }

    private static String range(int start, int end) {
        return start == end ? String.valueOf(start) : start + "-" + end;
    }

    private static SortedSet<String> derive() {
        SortedSet<String> codes = new TreeSet<>();
        for (int code = 1000; code <= 7999; code++) {
            String cfop = String.valueOf(code);
            if (RevenueCfops.classify(FiscalDirection.INBOUND, cfop) == RevenueCfops.Kind.PURCHASE) {
                codes.add(cfop);
            }
        }
        return Collections.unmodifiableSortedSet(codes);
    }
}
