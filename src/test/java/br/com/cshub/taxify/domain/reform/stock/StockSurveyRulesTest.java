package br.com.cshub.taxify.domain.reform.stock;

import br.com.cshub.taxify.domain.reform.stock.ReferenceRegime.Period;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures.GroupFigure;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures.InboundMonth;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures.MonthlyPurchases;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyFigures.PurchaseGroup;
import br.com.cshub.taxify.enumerated.StockSurveyClassification;
import br.com.cshub.taxify.enumerated.StockSurveyCoverage;
import br.com.cshub.taxify.enumerated.StockSurveyRegimeSource;
import br.com.cshub.taxify.enumerated.TaxRegime;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CE-RN-02 to CE-RN-06 and CE-RN-12 (spec 2026-11-27, F5a), with the spec's fictitious numbers. */
class StockSurveyRulesTest {

    // ------------------------------------------------------------------ CE-RN-02 reference regime

    /** CA-CE-01: Presumido since 2025-01, nothing for 2027. */
    @Test
    void presumedSince2025IsHypothesisI() {
        ReferenceRegime regime = ReferenceRegime.resolve(List.of(new Period(LocalDate.of(2025, 1, 1), TaxRegime.LUCRO_PRESUMIDO)), null);
        assertThat(regime.regime()).isEqualTo(TaxRegime.LUCRO_PRESUMIDO);
        assertThat(regime.source()).isEqualTo(StockSurveyRegimeSource.HISTORY);
        assertThat(regime.nextYearRegime()).isNull();
        assertThat(StockSurveyRule.of(regime)).isEqualTo(StockSurveyRule.PRESUMED_PROFIT);
        assertThat(StockSurveyRule.of(regime).classification()).isEqualTo(StockSurveyClassification.HYPOTHESIS_I);
    }

    /** CT-CE-05: no period and no registry regime. */
    @Test
    void noPeriodFallsBackToTheRegistry() {
        ReferenceRegime regime = ReferenceRegime.resolve(List.of(), null);
        assertThat(regime.source()).isEqualTo(StockSurveyRegimeSource.REGISTRATION);
        assertThat(regime.regime()).isNull();
        assertThat(StockSurveyRule.of(regime).classification()).isEqualTo(StockSurveyClassification.NO_REGIME);
        assertThat(StockSurveyRule.of(ReferenceRegime.resolve(List.of(), TaxRegime.OTHER)).classification())
                .isEqualTo(StockSurveyClassification.NO_REGIME);
        assertThat(StockSurveyRule.of(ReferenceRegime.resolve(List.of(), TaxRegime.LUCRO_REAL)).classification())
                .isEqualTo(StockSurveyClassification.HYPOTHESIS_II_III);
    }

    /** CT-CE-06 and CT-CE-08: Simples since always, Presumido from 2026-07-01; the 2026 period is not a 2027 one. */
    @Test
    void latestPeriodUpToDecember2026Wins() {
        ReferenceRegime regime = ReferenceRegime.resolve(List.of(new Period(null, TaxRegime.SIMPLES_NACIONAL),
                new Period(LocalDate.of(2026, 7, 1), TaxRegime.LUCRO_PRESUMIDO)), TaxRegime.SIMPLES_NACIONAL);
        assertThat(regime.regime()).isEqualTo(TaxRegime.LUCRO_PRESUMIDO);
        assertThat(regime.since()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(regime.nextYearRegime()).isNull();
        assertThat(StockSurveyRule.of(regime).classification()).isEqualTo(StockSurveyClassification.HYPOTHESIS_I);
    }

    /** A period starting on 2026-12-01 is the one of December; one starting on 2027-01-01 is not. */
    @Test
    void decemberFirstIsInAndJanuaryIsOut() {
        ReferenceRegime regime = ReferenceRegime.resolve(List.of(new Period(LocalDate.of(2025, 1, 1), TaxRegime.SIMPLES_NACIONAL),
                new Period(LocalDate.of(2026, 12, 1), TaxRegime.LUCRO_REAL)), null);
        assertThat(regime.regime()).isEqualTo(TaxRegime.LUCRO_REAL);
        assertThat(regime.since()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    /** CA-CE-02 and CT-CE-07: Simples from 2027-01 beats the 2026 regime (rule 1 before rules 2 and 3). */
    @Test
    void simplesFrom2027IsOutOfScope() {
        ReferenceRegime presumed = ReferenceRegime.resolve(List.of(new Period(LocalDate.of(2025, 1, 1), TaxRegime.LUCRO_PRESUMIDO),
                new Period(LocalDate.of(2027, 1, 1), TaxRegime.SIMPLES_NACIONAL)), null);
        assertThat(presumed.regime()).isEqualTo(TaxRegime.LUCRO_PRESUMIDO);
        assertThat(presumed.nextYearRegime()).isEqualTo(TaxRegime.SIMPLES_NACIONAL);
        assertThat(StockSurveyRule.of(presumed)).isEqualTo(StockSurveyRule.NEXT_YEAR_SIMPLES);
        assertThat(StockSurveyRule.of(presumed).classification()).isEqualTo(StockSurveyClassification.OUT_OF_SCOPE);

        ReferenceRegime real = ReferenceRegime.resolve(List.of(new Period(LocalDate.of(2025, 1, 1), TaxRegime.LUCRO_REAL),
                new Period(LocalDate.of(2027, 1, 1), TaxRegime.MEI)), null);
        assertThat(StockSurveyRule.of(real)).isEqualTo(StockSurveyRule.NEXT_YEAR_SIMPLES);
    }

    /** CA-CE-03 and rule 4. */
    @Test
    void realIsHypothesesIIAndIIIAndSimplesInDecemberIsOut() {
        assertThat(StockSurveyRule.of(TaxRegime.LUCRO_REAL, null).classification()).isEqualTo(StockSurveyClassification.HYPOTHESIS_II_III);
        assertThat(StockSurveyRule.of(TaxRegime.LUCRO_REAL, TaxRegime.LUCRO_PRESUMIDO)).isEqualTo(StockSurveyRule.REAL_PROFIT);
        assertThat(StockSurveyRule.of(TaxRegime.SIMPLES_NACIONAL, null)).isEqualTo(StockSurveyRule.SIMPLES);
        assertThat(StockSurveyRule.of(TaxRegime.MEI, null).classification()).isEqualTo(StockSurveyClassification.OUT_OF_SCOPE);
    }

    // ------------------------------------------------------------------ CE-RN-04 purchase CFOPs

    /** CT-CE-01 and CT-CE-02: supplier sale CFOPs and the own entry 1102 are purchases; 5949 is not. */
    @Test
    void purchaseCfopsComeFromTheAssessment() {
        assertThat(PurchaseCfops.codes()).contains("5102", "5405", "6102", "1102", "1101", "2102", "1126")
                .doesNotContain("5949", "1949", "5201", "1201", "5910", "0102", "8102");
        String compacted = PurchaseCfops.compacted();
        assertThat(compacted).startsWith("1101-1102, 1126, 1401, 1403, 2101-2102");
        assertThat(compacted).contains("5101-5106");
    }

    // ------------------------------------------------------------------ CE-RN-04 to CE-RN-06 figures

    /** CT-CE-01: the canceled note and the 5949 item never reach the aggregates (the query leaves them out). */
    @Test
    void figuresOfTheSpecExample() {
        List<MonthlyPurchases> months = List.of(new MonthlyPurchases(3, new BigDecimal("120000.00"), 1, 1),
                new MonthlyPurchases(5, new BigDecimal("40000.00"), 2, 2));
        List<PurchaseGroup> csts = List.of(new PurchaseGroup("01", new BigDecimal("120000.00"), 1),
                new PurchaseGroup("04", new BigDecimal("30000.00"), 1), new PurchaseGroup("06", new BigDecimal("10000.00"), 1));
        List<PurchaseGroup> ncms = List.of(new PurchaseGroup("22030000", new BigDecimal("130000.00"), 2),
                new PurchaseGroup("22021000", new BigDecimal("30000.00"), 1));

        StockSurveyFigures figures = StockSurveyFigures.of(months, csts, ncms,
                List.of(new InboundMonth(3, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 30))), LocalDate.of(2026, 12, 10));

        assertThat(figures.purchasesTotal()).hasToString("160000.00");
        assertThat(figures.purchaseItems()).isEqualTo(3);
        assertThat(figures.inboundNotes()).isEqualTo(3);
        assertThat(figures.purchasesByCstPis()).extracting(GroupFigure::key).containsExactly("01", "04", "06");
        assertThat(figures.purchasesByCstPis().getFirst().value()).hasToString("120000.00");
        assertThat(figures.topNcm()).containsExactly(new GroupFigure("22030000", new BigDecimal("130000.00"), 2),
                new GroupFigure("22021000", new BigDecimal("30000.00"), 1));
        assertThat(figures.purchasesByMonth()).hasSize(12);
        assertThat(figures.purchasesByMonth().get(2).month()).isEqualTo("2026-03");
        assertThat(figures.purchasesByMonth().get(2).value()).hasToString("120000.00");
        assertThat(figures.purchasesByMonth().get(0).value()).hasToString("0.00");
        assertThat(figures.purchasesByMonth().get(0).notes()).isZero();
    }

    /** CT-CE-12 and CT-CE-32: no inbound note; a null item value counts zero in the value and 1 in the items. */
    @Test
    void noInboundAndNullValues() {
        StockSurveyFigures none = StockSurveyFigures.of(List.of(), List.of(), List.of(), List.of(), LocalDate.of(2026, 12, 10));
        assertThat(none.purchasesTotal()).hasToString("0.00");
        assertThat(none.coverage()).isEqualTo(StockSurveyCoverage.NO_INBOUND);
        assertThat(none.purchasesByMonth()).hasSize(12).allSatisfy(month -> assertThat(month.value()).hasToString("0.00"));

        StockSurveyFigures nullValue = StockSurveyFigures.of(List.of(new MonthlyPurchases(4, null, 1, 1)),
                List.of(new PurchaseGroup(null, null, 1)), List.of(new PurchaseGroup("  ", null, 1)), List.of(),
                LocalDate.of(2026, 12, 10));
        assertThat(nullValue.purchasesTotal()).hasToString("0.00");
        assertThat(nullValue.purchaseItems()).isEqualTo(1);
        assertThat(nullValue.purchasesByCstPis()).containsExactly(new GroupFigure(StockSurveyFigures.NO_CST, new BigDecimal("0.00"), 1));
        assertThat(nullValue.topNcm()).extracting(GroupFigure::key).containsExactly(StockSurveyFigures.NO_NCM);
    }

    /** CE-RN-05: blank and null CST merge; ties by key ascending; top 10 NCMs. */
    @Test
    void groupsMergeSortAndCut() {
        List<PurchaseGroup> csts = List.of(new PurchaseGroup(null, new BigDecimal("5.00"), 1),
                new PurchaseGroup("", new BigDecimal("5.00"), 2), new PurchaseGroup("50", new BigDecimal("10.00"), 1),
                new PurchaseGroup("01", new BigDecimal("10.00"), 1));
        assertThat(StockSurveyFigures.grouped(csts, StockSurveyFigures.NO_CST, Integer.MAX_VALUE))
                .containsExactly(new GroupFigure("01", new BigDecimal("10.00"), 1), new GroupFigure("50", new BigDecimal("10.00"), 1),
                        new GroupFigure(StockSurveyFigures.NO_CST, new BigDecimal("10.00"), 3));

        List<PurchaseGroup> ncms = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            ncms.add(new PurchaseGroup("2203%04d".formatted(i), new BigDecimal(i * 100), 1));
        }
        List<GroupFigure> top = StockSurveyFigures.grouped(ncms, StockSurveyFigures.NO_NCM, StockSurveyFigures.TOP_NCM);
        assertThat(top).hasSize(10);
        assertThat(top.getFirst().key()).isEqualTo("22030012");
        assertThat(top.getLast().key()).isEqualTo("22030003");
    }

    /** CA-CE-14, CT-CE-09 to CT-CE-11: coverage by the months evaluated (Recife date). */
    @Test
    void coverage() {
        List<InboundMonth> octToDec = List.of(new InboundMonth(10, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 20)),
                new InboundMonth(11, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 28)),
                new InboundMonth(12, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 9)));
        InboundCoverage december = InboundCoverage.of(octToDec, LocalDate.of(2026, 12, 10));
        assertThat(december.monthsWithInbound()).isEqualTo(3);
        assertThat(december.monthsEvaluated()).isEqualTo(11);
        assertThat(december.coverage()).isEqualTo(StockSurveyCoverage.PARTIAL);
        assertThat(december.first()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(december.last()).isEqualTo(LocalDate.of(2026, 12, 9));

        InboundCoverage onlyOctNov = InboundCoverage.of(octToDec.subList(0, 2), LocalDate.of(2026, 12, 10));
        assertThat(onlyOctNov.monthsWithInbound()).isEqualTo(2);
        assertThat(onlyOctNov.coverage()).isEqualTo(StockSurveyCoverage.PARTIAL);

        List<InboundMonth> janToNov = new ArrayList<>();
        for (int month = 1; month <= 11; month++) {
            janToNov.add(new InboundMonth(month, LocalDate.of(2026, month, 1), LocalDate.of(2026, month, 1)));
        }
        assertThat(InboundCoverage.of(janToNov, LocalDate.of(2026, 12, 10)).coverage()).isEqualTo(StockSurveyCoverage.COMPLETE);

        InboundCoverage january = InboundCoverage.of(octToDec, LocalDate.of(2027, 1, 2));
        assertThat(january.monthsEvaluated()).isEqualTo(12);
        assertThat(january.coverage()).isEqualTo(StockSurveyCoverage.PARTIAL);
        assertThat(InboundCoverage.of(janToNov, LocalDate.of(2027, 1, 2)).coverage()).isEqualTo(StockSurveyCoverage.PARTIAL);
    }

    // ------------------------------------------------------------------ CE-RN-12 closing

    /** CT-CE-27: open until 2027-06-30 23:59 of Recife, closed from 2027-07-01 00:00. */
    @Test
    void closesOnTheFirstOfJuly2027() {
        assertThat(StockSurveyCalendar.isClosed(LocalDateTime.of(2027, 6, 30, 23, 59, 59))).isFalse();
        assertThat(StockSurveyCalendar.isClosed(LocalDateTime.of(2027, 7, 1, 0, 0))).isTrue();
        assertThat(StockSurveyCalendar.isClosed(LocalDateTime.of(2026, 12, 10, 6, 15))).isFalse();
        assertThat(StockSurveyCalendar.isValidYear(2026)).isTrue();
        assertThat(StockSurveyCalendar.isValidYear(2025)).isFalse();
        assertThat(StockSurveyCalendar.isValidYear(null)).isFalse();
    }
}
