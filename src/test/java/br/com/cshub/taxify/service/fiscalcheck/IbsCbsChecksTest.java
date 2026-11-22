package br.com.cshub.taxify.service.fiscalcheck;

import br.com.cshub.taxify.domain.reform.IbsCbsRate;
import br.com.cshub.taxify.domain.xml.FiscalIbsCbsTotals;
import br.com.cshub.taxify.domain.xml.FiscalItemIbsCbs;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.DivergenceSeverity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IbsCbsChecksTest {

    private final FiscalCheckContext context = new FiscalCheckContext(UUID.randomUUID(), Map.of(),
            Map.of(2026, new IbsCbsRate(2026, new BigDecimal("0.9"), new BigDecimal("0.1"), BigDecimal.ZERO)));
    private final IbsCbsItemCheck itemCheck = new IbsCbsItemCheck();
    private final IbsCbsDocumentCheck documentCheck = new IbsCbsDocumentCheck();

    @Test
    void correctItemHasNoFinding() {
        FiscalXmlDocumentItem item = item(document(2026, "3"), group("000", "000001", "100", "0.1", null, "0.10", "0.9", null, "0.90", false));

        assertThat(itemCheck.check(item, context)).isEmpty();
    }

    @Test
    void reducedRateIsCalculatedWithTheReduction() {
        // CBS 0,9% com 60% de redução = 0,36% de 100
        FiscalXmlDocumentItem item = item(document(2026, "3"), group("200", "200001", "100", "0.1", "60", "0.04", "0.9", "60", "0.36", false));

        assertThat(itemCheck.check(item, context)).isEmpty();
    }

    @Test
    void flagsWrongRateWrongValueAndClassTribOfAnotherCst() {
        FiscalXmlDocumentItem item = item(document(2026, "3"), group("000", "200001", "100", "0.1", null, "0.10", "1.0", null, "0.90", false));

        List<FiscalDivergence> found = itemCheck.check(item, context);

        assertThat(found).extracting(FiscalDivergence::code, FiscalDivergence::field)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(IbsCbsItemCheck.CLASS_TRIB, "cClassTrib"),
                        org.assertj.core.groups.Tuple.tuple(IbsCbsItemCheck.RATE, "pCBS"),
                        org.assertj.core.groups.Tuple.tuple(IbsCbsItemCheck.CALCULATION, "vCBS"));
        FiscalDivergence rate = found.stream().filter(d -> d.code().equals(IbsCbsItemCheck.RATE)).findFirst().orElseThrow();
        assertThat(rate.found()).isEqualTo("1");
        assertThat(rate.expected()).isEqualTo("0.9");
        assertThat(rate.severity()).isEqualTo(DivergenceSeverity.ERROR);
    }

    @Test
    void noRateCheckForAYearWithoutReferenceOrUntaxedCstAndNoCalculationWithDeferral() {
        FiscalXmlDocumentItem year2027 = item(document(2027, "3"), group("000", "000001", "100", "5", null, "5.00", "8", null, "8.00", false));
        FiscalXmlDocumentItem immune = item(document(2026, "3"), group("410", "410001", "100", "0.5", null, "0.50", "2", null, "2.00", false));
        FiscalXmlDocumentItem deferred = item(document(2026, "3"), group("510", "510001", "100", "0.1", null, "0.00", "0.9", null, "0.00", true));

        assertThat(itemCheck.check(year2027, context)).isEmpty();
        assertThat(itemCheck.check(immune, context)).isEmpty();
        assertThat(itemCheck.check(deferred, context)).isEmpty();
    }

    @Test
    void warnsWhenARegimeNormalNoteFrom2026HasNoGroup() {
        FiscalXmlDocumentItem normal = new FiscalXmlDocumentItem();
        FiscalXmlDocumentItem simples = new FiscalXmlDocumentItem();
        FiscalXmlDocumentItem before = new FiscalXmlDocumentItem();

        assertThat(documentCheck.check(document(2026, "3"), List.of(normal), context))
                .extracting(FiscalDivergence::code).containsExactly(IbsCbsDocumentCheck.MISSING);
        assertThat(documentCheck.check(document(2026, "1"), List.of(simples), context)).isEmpty();
        assertThat(documentCheck.check(document(2025, "3"), List.of(before), context)).isEmpty();
    }

    @Test
    void totalsMustMatchTheSumOfTheItems() {
        FiscalXmlDocument document = document(2026, "3");
        FiscalXmlDocumentItem a = item(document, group("000", "000001", "100", "0.1", null, "0.10", "0.9", null, "0.90", false));
        FiscalXmlDocumentItem b = item(document, group("000", "000001", "50", "0.1", null, "0.05", "0.9", null, "0.45", false));

        document.setIbsCbsTotals(new FiscalIbsCbsTotals(new BigDecimal("150"), new BigDecimal("0.15"), BigDecimal.ZERO,
                new BigDecimal("0.15"), new BigDecimal("1.35")));
        assertThat(documentCheck.check(document, List.of(a, b), context)).isEmpty();

        document.setIbsCbsTotals(new FiscalIbsCbsTotals(new BigDecimal("150"), new BigDecimal("0.15"), BigDecimal.ZERO,
                new BigDecimal("0.15"), new BigDecimal("1.50")));
        assertThat(documentCheck.check(document, List.of(a, b), context))
                .extracting(FiscalDivergence::code, FiscalDivergence::field, FiscalDivergence::expected)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(IbsCbsDocumentCheck.TOTALS, "vCBS", "1.35"));
    }

    private static FiscalXmlDocument document(int year, String crt) {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setId(UUID.randomUUID());
        document.setDocumentType("NFE");
        document.setIssueDate(LocalDateTime.of(year, 3, 10, 9, 0));
        document.setEmitenteRegimeTributario(crt);
        return document;
    }

    private static FiscalXmlDocumentItem item(FiscalXmlDocument document, FiscalItemIbsCbs group) {
        FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
        item.setDocument(document);
        item.setIbsCbs(group);
        return item;
    }

    private static FiscalItemIbsCbs group(String cst, String classTrib, String base, String pIbsUf, String redIbsUf,
                                          String vIbsUf, String pCbs, String redCbs, String vCbs, boolean deferral) {
        BigDecimal valorIbsUf = new BigDecimal(vIbsUf);
        return new FiscalItemIbsCbs(cst, classTrib, new BigDecimal(base),
                new BigDecimal(pIbsUf), redIbsUf == null ? null : new BigDecimal(redIbsUf), valorIbsUf,
                BigDecimal.ZERO, null, BigDecimal.ZERO, valorIbsUf,
                new BigDecimal(pCbs), redCbs == null ? null : new BigDecimal(redCbs), new BigDecimal(vCbs), deferral);
    }
}
